package io.nayuki.deflate;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;


/**
 * Decompresses a DEFLATE data stream (raw format without zlib or gzip headers or footers) into a byte stream.
 * <p>Incomplete functionality - currently only supports uncompressed blocks.</p>
 */
public final class InflaterInputStream extends FilterInputStream {
	
	// -3: A data format exception has been thrown.
	// -2: This inflater stream has been closed.
	// 0 to 65535: Number of bytes remaining in current uncompressed block.
	private int state;
	
	// Indicates whether a block header with the "bfinal" flag has been seen.
	private boolean isLastBlock;
	
	
	
	protected InflaterInputStream(InputStream in) {
		super(in);
		state = 0;
		isLastBlock = false;
	}
	
	
	
	public int read() throws IOException {
		// Check conditions and throw exceptions
		if (state == -3)
			throw new IOException("The stream contained invalid data");
		if (state == -2)
			throw new IllegalStateException("Stream already closed");
		
		// Try to fill with data
		if (state == 0) {
			processBlockHeader();
			if (state == 0) {
				assert isLastBlock;
				return -1;
			}
		}
		
		// Read a byte
		assert 1 <= state && state <= 0xFFFF;
		int b = in.read();
		if (b == -1)
			unexpectedEof();
		state--;
		return b;
	}
	
	
	public int read(byte[] b, int off, int len) throws IOException {
		// Check conditions and throw exceptions
		if (state == -3)
			throw new IOException("The stream contained invalid data");
		if (state == -2)
			throw new IllegalStateException("Stream already closed");
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new IndexOutOfBoundsException();
		
		// Try to fill with data
		if (state == 0) {
			if (isLastBlock)
				return -1;
			if (len == 0)
				return 0;
			processBlockHeader();
			if (state == 0) {
				assert isLastBlock;
				return -1;
			}
		}
		
		assert 1 <= state && state <= 0xFFFF;
		int n = Math.min(state, len);
		readFully(b, off, n);
		state -= n;
		return n;
	}
	
	
	public void close() throws IOException {
		state = -2;
		isLastBlock = true;
		super.close();
	}
	
	
	private void processBlockHeader() throws IOException {
		while (state == 0 && !isLastBlock) {
			int header = in.read();
			if (header == -1)
				unexpectedEof();
			isLastBlock = (header & 1) != 0;
			int type = (header >>> 1) & 3;
			if (type != 0)
				throw new UnsupportedOperationException("Only uncompressed blocks supported");
			
			byte[] b = new byte[4];
			readFully(b, 0, b.length);
			int len  = (b[0] & 0xFF) | (b[1] & 0xFF) << 8;
			int nlen = (b[2] & 0xFF) | (b[3] & 0xFF) << 8;
			if (len != (nlen ^ 0xFFFF))
				invalidData("len/nlen mismatch in uncompressed block");
			state = len;
		}
	}
	
	
	private void invalidData(String reason) throws IOException {
		state = -3;
		isLastBlock = true;
		throw new IOException("Invalid DEFLATE data: " + reason);
	}
	
	
	private void unexpectedEof() throws EOFException {
		state = -3;
		isLastBlock = true;
		throw new EOFException("Unexpected end of stream");
	}
	
	
	private void readFully(byte[] b, int off, int len) throws IOException {
		while (len > 0) {
			int n = in.read(b, off, len);
			if (n == -1)
				unexpectedEof();
			off += n;
			len -= n;
		}
	}
	
}
