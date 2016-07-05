/* 
 * DEFLATE library (Java)
 * Copyright (c) Project Nayuki
 * 
 * https://www.nayuki.io/page/deflate-library-java
 * https://github.com/nayuki/DEFLATE-library-Java
 */

package io.nayuki.deflate;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;


/**
 * Compresses a byte stream into a DEFLATE data stream (raw format without zlib or gzip headers or footers).
 * <p>Currently only supports uncompressed blocks for simplicity, which actually
 * expands the data slightly, but still conforms to the DEFLATE format.</p>
 * <p>This class performs its own buffering, so it is unnecessary to wrap a
 * {@link BufferedOutputStream} around the {@link OutputStream} given to the constructor.</p>
 */
public final class DeflaterOutputStream extends FilterOutputStream {
	
	private byte[] buffer;
	private int index;
	private boolean isFinished;
	
	
	
	public DeflaterOutputStream(OutputStream out) {
		super(out);
		buffer = new byte[5 + 65535];
		index = 5;
		isFinished = false;
	}
	
	
	
	public void write(int b) throws IOException {
		if (isFinished)
			throw new IllegalStateException();
		if (index == buffer.length)
			writeBuffer(false);
		buffer[index] = (byte)b;
		index++;
	}
	
	
	public void write(byte[] b, int off, int len) throws IOException {
		if (isFinished)
			throw new IllegalStateException();
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new IndexOutOfBoundsException();
		while (len > 0) {
			if (index == buffer.length)
				writeBuffer(false);
			int chunk = Math.min(len, buffer.length - index);
			System.arraycopy(b, off, buffer, index, chunk);
			off += chunk;
			len -= chunk;
			index += chunk;
		}
	}
	
	
	public void flush() throws IOException {
		if (isFinished)
			throw new IllegalStateException();
		if (index > 5)  // If current block is not empty
			writeBuffer(false);
		out.flush();
	}
	
	
	public void finish() throws IOException {
		if (isFinished)
			throw new IllegalStateException();
		writeBuffer(true);
		isFinished = true;
	}
	
	
	public void close() throws IOException {
		if (!isFinished)
			finish();
		out.close();
	}
	
	
	private void writeBuffer(boolean isFinal) throws IOException {
		if (isFinished)
			throw new IllegalStateException();
		
		// Fill in header fields
		int len = index - 5;
		int nlen = len ^ 0xFFFF;
		buffer[0] = (byte)(isFinal ? 0x01 : 0x00);
		buffer[1] = (byte)(len >>> 0);
		buffer[2] = (byte)(len >>> 8);
		buffer[3] = (byte)(nlen >>> 0);
		buffer[4] = (byte)(nlen >>> 8);
		
		// Write and reset
		out.write(buffer, 0, index);
		index = 5;
	}
	
}
