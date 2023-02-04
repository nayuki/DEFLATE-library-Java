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
 * Decompresses a DEFLATE data stream (raw format without zlib or gzip headers or footers) into
 * a byte stream. Objects only use memory and no operating system resources, so it is safe to discard
 * these objects without calling {@link #close()} in order to continue using the underlying streams.
 * @see DeflaterOutputStream
 */
public final class InflaterInputStream extends InputStream {
	
	/*---- Field ----*/
	
	private State state;
	
	
	
	/*---- Constructors ----*/
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream. The
	 * underlying stream must contain DEFLATE-compressed data with no headers or footers (e.g. must
	 * be unwrapped from the zlib or gzip container formats). When this inflater stream reaches the end,
	 * the underlying stream will be at an unspecified position at or after the end of the DEFLATE data.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @throws NullPointerException if the input stream is {@code null}
	 */
	public InflaterInputStream(InputStream in) {
		this(in, false);
	}
	
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream,
	 * and with the specified option for ending exactly. The underlying stream must
	 * contain DEFLATE-compressed data with no headers or footers (e.g. must be unwrapped
	 * from the zlib or gzip container formats). If ending exactly is requested, then
	 * the underlying stream must support marking, and when this inflater stream reaches
	 * the end, the underlying stream will be foremost byte position after the end of the
	 * DEFLATE data. Otherwise (not ending exactly) when this inflater stream reaches the
	 * end, the underlying stream will be at an unspecified position at or after the end
	 * of the DEFLATE data. For end-exactly to be useful, discard this inflater stream
	 * without calling {@link #close()} so that the underlying stream can still be used.
	 * @param in the underlying input stream of raw DEFLATE-compressed data
	 * @param endExactly whether to position the underlying stream at the exact
	 * position after the end of the DEFLATE data when this inflater stream ends
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code endExactly
	 * == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean endExactly) {
		this(in, endExactly, DEFAULT_INPUT_BUFFER_SIZE);
	}
	
	
	private static final int DEFAULT_INPUT_BUFFER_SIZE = 16 * 1024;
	
	
	/**
	 * Constructs an inflater input stream over the specified underlying input stream,
	 * with the specified options for ending exactly and input buffer size. The underlying
	 * stream must contain DEFLATE-compressed data with no headers or footers (e.g. must
	 * be unwrapped from the zlib or gzip container formats). If ending exactly is
	 * requested, then the underlying stream must support marking, and when this inflater
	 * stream reaches the end, the underlying stream will be foremost byte position after
	 * the end of the DEFLATE data. Otherwise (not ending exactly) when this inflater
	 * stream reaches the end, the underlying stream will be at an unspecified position
	 * at or after the end of the DEFLATE data. For end-exactly to be useful, discard this
	 * inflater stream without calling {@link #close()} so that the underlying stream can
	 * still be used.
	 * @param in the underlying input stream of raw DEFLATE-compressed data (not {@code null})
	 * @param endExactly whether to position the underlying stream at the exact
	 * position after the end of the DEFLATE data when this inflater stream ends
	 * @param inBufLen the size of the internal read buffer, which must be positive
	 * @throws NullPointerException if the input stream is {@code null}
	 * @throws IllegalArgumentException if {@code inBufLen < 1}
	 * @throws IllegalArgumentException if {@code endExactly
	 * == true} but {@code in.markSupported() == false}
	 */
	public InflaterInputStream(InputStream in, boolean endExactly, int inBufLen) {
		Objects.requireNonNull(in);
		if (inBufLen <= 0)
			throw new IllegalArgumentException("Non-positive input buffer size");
		if (endExactly) {
			if (!in.markSupported())
				throw new IllegalArgumentException("Input stream not markable, cannot support detachment");
			in.mark(0);
		}
		state = new Open(in, endExactly, inBufLen);
	}
	
	
	
	/*---- Methods ----*/
	
	/**
	 * Reads the next byte of decompressed data from this stream. If data is
	 * available then a number in the range [0, 255] is returned (blocking if
	 * necessary); otherwise &minus;1 is returned if the end of stream is reached.
	 * @return the next unsigned byte of data, or &minus;1 for the end of stream
	 * @throws IOException if an I/O exception occurs in the underlying input stream, the end
	 * of stream occurs at an unexpected position, or the compressed data has a format error
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
	 * Reads some bytes from the decompressed data of this stream into the specified array's
	 * subrange. This returns the number of data bytes that were stored into the array, and
	 * is in the range [&minus;1, len]. A return value of 0 is allowed iff {@code len} is 0.
	 * @throws NullPointerException if the array is {@code null}
	 * @throws ArrayIndexOutOfBoundsException if the array subrange is out of bounds
	 * @throws IOException if an I/O exception occurs in the underlying input stream, the end of
	 * stream occurs at an unexpected position, or the compressed data has a format error
	 * @throws IllegalStateException if the stream has already been closed
	 */
	@Override public int read(byte[] b, int off, int len) throws IOException {
		// Check arguments and state
		Objects.requireNonNull(b);
		Objects.checkFromIndexSize(off, len, b.length);
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
	 * Closes this input stream and the underlying stream. It is illegal
	 * to call {@link #read()} or {@link #detach()} after closing. It is
	 * idempotent to call this {@link #close()} method more than once.
	 * @throws IOException if an I/O exception occurs in the underlying stream
	 */
	@Override public void close() throws IOException {
		if (state instanceof Open st)
			st.close();
		else if (state instanceof StickyException st)
			st.input().close();
		state = Closed.SINGLETON;
	}
	
}
