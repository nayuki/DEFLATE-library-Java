/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;


/**
 * Compresses a byte stream into a DEFLATE data stream (raw format without zlib or gzip headers or footers).
 * <p>Currently only supports uncompressed blocks for simplicity, which actually
 * expands the data slightly, but still conforms to the DEFLATE format.</p>
 * <p>This class performs its own buffering, so it is unnecessary to wrap a
 * {@link BufferedOutputStream} around the {@link OutputStream} given to the constructor.</p>
 * @see InflaterInputStream
 */
public final class DeflaterOutputStream extends OutputStream {
	
	private OutputStream output;
	private byte[] buffer;
	private int index;
	
	
	
	public DeflaterOutputStream(OutputStream out) {
		this.output = Objects.requireNonNull(out);
		buffer = new byte[5 + 65535];
		index = 5;
	}
	
	
	
	@Override public void write(int b) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		if (index == buffer.length)
			writeBuffer(false);
		buffer[index] = (byte)b;
		index++;
	}
	
	
	@Override public void write(byte[] b, int off, int len) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		Objects.checkFromIndexSize(off, len, b.length);
		while (len > 0) {
			if (index == buffer.length)
				writeBuffer(false);
			int n = Math.min(len, buffer.length - index);
			System.arraycopy(b, off, buffer, index, n);
			off += n;
			len -= n;
			index += n;
		}
	}
	
	
	@Override public void close() throws IOException {
		if (output != null) {
			writeBuffer(true);
			output.close();
			output = null;
		}
	}
	
	
	private void writeBuffer(boolean isFinal) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		
		// Fill in header fields
		int len = index - 5;
		int nlen = len ^ 0xFFFF;
		buffer[0] = (byte)(isFinal ? 0x01 : 0x00);
		buffer[1] = (byte)(len >>> 0);
		buffer[2] = (byte)(len >>> 8);
		buffer[3] = (byte)(nlen >>> 0);
		buffer[4] = (byte)(nlen >>> 8);
		
		// Write and reset
		output.write(buffer, 0, index);
		index = 5;
	}
	
}
