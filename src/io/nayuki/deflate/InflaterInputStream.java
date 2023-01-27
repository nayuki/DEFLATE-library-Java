/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import io.nayuki.deflate.decomp.Closed;
import io.nayuki.deflate.decomp.Open;
import io.nayuki.deflate.decomp.State;
import io.nayuki.deflate.decomp.StickyException;


/**
 * Decompresses a DEFLATE data stream (raw format without zlib or gzip headers or footers) into a byte stream.
 */
public final class InflaterInputStream extends InputStream {
	
	/*---- Field ----*/
	
	private State state;
	
	
	/*---- Constructors ----*/
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream. The
	 * underlying stream must contain DEFLATE-compressed data with no headers or footers (e.g. must
	 * be unwrapped from the zlib or gzip container formats). {@code detach()} cannot be called.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @throws NullPointerException if the input stream is {@code null}
	 */
	public InflaterInputStream(InputStream in) {
		this(in, false);
	}
	
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream, and with the
	 * specified option for detachability. The underlying stream must contain DEFLATE-compressed data with
	 * no headers or footers (e.g. must be unwrapped from the zlib or gzip container formats). Detachability
	 * allows {@link #detach()} to be called, and requires the specified input stream to support marking.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @param detachable whether {@code detach()} can be called later
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code detach == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean detachable) {
		this(in, detachable, DEFAULT_INPUT_BUFFER_SIZE);
	}
	
	
	private static final int DEFAULT_INPUT_BUFFER_SIZE = 16 * 1024;
	
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream, with the
	 * specified options for detachability and input buffer size. The underlying stream must
	 * contain DEFLATE-compressed data with no headers or footers (e.g. must be unwrapped from
	 * the zlib or gzip container formats). Detachability allows {@link #detach()} to be called,
	 * and requires the specified input stream to support marking.
	 * @param in the underlying input stream of raw DEFLATE-compressed data (not {@code null})
	 * @param detachable whether {@code detach()} can be called later
	 * @param inBufLen the size of the internal read buffer, which must be positive
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code inBufLen < 1}
	 * @throws IllegalArgumentException if {@code detach == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean detachable, int inBufLen) {
		state = new Open(in, detachable, inBufLen);
	}
	
	
	/*---- Public API methods ----*/
	
	/**
	 * Reads the next byte of decompressed data from this stream. If data is available
	 * then a number in the range [0, 255] is returned (blocking if necessary);
	 * otherwise &minus;1 is returned if the end of stream is reached.
	 * @return the next unsigned byte of data, or &minus;1 for the end of stream
	 * @throws IOException if an I/O exception occurred in the underlying input stream, the end of
	 * stream was encountered at an unexpected position, or the compressed data has a format error
	 * @throws IllegalStateException if the stream has already been closed
	 */
	@Override public int read() throws IOException {
		// In theory this method for reading a single byte could be implemented somewhat faster.
		// We could take the logic of read(byte[],int,int) and simplify it for the special case
		// of handling one byte. But if the caller chose to use this read() method instead of
		// the bulk read(byte[]) method, then they have already chosen to not care about speed.
		// Therefore speeding up this method would result in needless complexity. Instead,
		// we chose to optimize this method for simplicity and ease of verifying correctness.
		var b = new byte[1];
		return switch (read(b)) {
			case  1 -> b[0] & 0xFF;
			case -1 -> -1;  // EOF
			default -> throw new AssertionError("Unreachable value");
		};
	}
	
	
	/**
	 * Reads some bytes from the decompressed data of this stream into the specified array's subrange.
	 * This returns the number of data bytes that were stored into the array, and is in the range
	 * [&minus;1, len]. Note that 0 can be returned even if the end of stream hasn't been reached yet.
	 * @throws NullPointerException if the array is {@code null}
	 * @throws ArrayIndexOutOfBoundsException if the array subrange is out of bounds
	 * @throws IOException if an I/O exception occurred in the underlying input stream, the end of
	 * stream was encountered at an unexpected position, or the compressed data has a format error
	 * @throws IllegalStateException if the stream has already been closed
	 */
	@Override public int read(byte[] b, int off, int len) throws IOException {
		// Check arguments and state
		Objects.requireNonNull(b);
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new ArrayIndexOutOfBoundsException();
		if (state instanceof Open st) {
			try {
				return st.read(b, off, len);
			} catch (IOException e) {
				state = new StickyException(st.input, e);
				throw e;
			}
		} else if (state instanceof StickyException st)
			throw st.exception();
		else if (state instanceof Closed)
			throw new IllegalStateException("Stream already closed");
		else
			throw new AssertionError("Unreachable type");
	}
	
	
	/**
	 * Detaches the underlying input stream from this decompressor. This puts the underlying stream
	 * at the position of the first byte after the data that this decompressor actually consumed.
	 * Calling {@code detach()} invalidates this stream object but doesn't close the underlying stream.
	 * <p>This method exists because for efficiency, the decompressor may read more bytes from the
	 * underlying stream than necessary to produce the decompressed data. If you want to continue
	 * reading the underlying stream exactly after the point the DEFLATE-compressed data ends,
	 * then it is necessary to call this detach method.</p>
	 * <p>This can only be called once, and is mutually exclusive with respect to calling
	 * {@link #close()}. It is illegal to call {@link #read()} after detaching.</p>
	 * @throws IllegalStateException if detach was already called or this stream has been closed
	 * @throws IOException if an I/O exception occurred
	 */
	public void detach() throws IOException {
		if (state instanceof Open st) {
			st.detach();
			state = Closed.SINGLETON;
		} else if (state instanceof StickyException st)
			throw st.exception();
		else if (state instanceof Closed)
			throw new IllegalStateException("Input stream already detached/closed");
		else
			throw new AssertionError("Unreachable type");
	}
	
	
	/**
	 * Closes this input stream and the underlying stream.
	 * It is illegal to call {@link #read()} or {@link #detach()} after closing.
	 * It is idempotent to call this {@link #close()} method more than once.
	 * @throws IOException if an I/O exception occurred in the underlying stream
	 */
	@Override public void close() throws IOException {
		if (state instanceof Open st)
			st.close();
		else if (state instanceof StickyException st)
			st.input().close();
		state = Closed.SINGLETON;
	}
	
}
