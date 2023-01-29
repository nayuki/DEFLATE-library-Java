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
import io.nayuki.deflate.comp.BitOutputStream;
import io.nayuki.deflate.comp.Decision;
import io.nayuki.deflate.comp.Strategy;
import io.nayuki.deflate.comp.Uncompressed;


/**
 * Compresses a byte stream into a DEFLATE data stream (raw format without zlib or gzip headers or footers).
 * <p>Currently only supports uncompressed blocks for simplicity, which actually
 * expands the data slightly, but still conforms to the DEFLATE format.</p>
 * <p>This class performs its own buffering, so it is unnecessary to wrap a
 * {@link BufferedOutputStream} around the {@link OutputStream} given to the constructor.</p>
 * @see InflaterInputStream
 */
public final class DeflaterOutputStream extends OutputStream {
	
	private static final int HISTORY_CAPACITY = 32 * 1024;
	
	
	private OutputStream output;
	private BitOut bitOutput;
	private byte[] buffer;
	private int historyLength = 0;
	private int dataEndIndex = HISTORY_CAPACITY;
	
	
	public DeflaterOutputStream(OutputStream out) {
		this(out, 64 * 1024);  // Default buffer size
	}
	
	
	public DeflaterOutputStream(OutputStream out, int bufferLen) {
		this.output = Objects.requireNonNull(out);
		bitOutput = new BitOut();
		if (bufferLen < 1 || bufferLen > Integer.MAX_VALUE - HISTORY_CAPACITY)
			throw new IllegalArgumentException("Invalid buffer length");
		buffer = new byte[HISTORY_CAPACITY + bufferLen];
	}
	
	
	
	@Override public void write(int b) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		if (dataEndIndex == buffer.length)
			writeBuffer(false);
		buffer[dataEndIndex] = (byte)b;
		dataEndIndex++;
	}
	
	
	@Override public void write(byte[] b, int off, int len) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		Objects.checkFromIndexSize(off, len, b.length);
		while (len > 0) {
			if (dataEndIndex == buffer.length)
				writeBuffer(false);
			int n = Math.min(len, buffer.length - dataEndIndex);
			System.arraycopy(b, off, buffer, dataEndIndex, n);
			off += n;
			len -= n;
			dataEndIndex += n;
		}
	}
	
	
	@Override public void close() throws IOException {
		if (output != null) {
			writeBuffer(true);
			bitOutput.finish();
			output.close();
			output = null;
		}
	}
	
	
	private void writeBuffer(boolean isFinal) throws IOException {
		if (output == null)
			throw new IllegalStateException("Stream already closed");
		
		assert 0 <= historyLength && historyLength <= HISTORY_CAPACITY;
		assert HISTORY_CAPACITY <= dataEndIndex && dataEndIndex <= buffer.length;
		int historyStart = HISTORY_CAPACITY - historyLength;
		int dataLen = dataEndIndex - HISTORY_CAPACITY;
		
		Strategy st = Uncompressed.SINGLETON;
		Decision dec = st.decide(buffer, historyStart, historyLength, dataLen);
		dec.compressTo(bitOutput, isFinal);
		
		if (!isFinal) {
			int n = Math.min(dataEndIndex - HISTORY_CAPACITY, HISTORY_CAPACITY);
			System.arraycopy(buffer, dataEndIndex - n, buffer, HISTORY_CAPACITY - n, n);
			historyLength = Math.min(dataEndIndex - historyStart, HISTORY_CAPACITY);
			dataEndIndex = HISTORY_CAPACITY;
		}
	}
	
	
	
	private final class BitOut implements BitOutputStream {
		
		private long bitBuffer = 0;
		private int bitBufferLength = 0;
		
		
		@Override public void writeBits(int value, int numBits) throws IOException {
			assert 0 <= numBits && numBits <= 31 && value >>> numBits == 0;
			if (numBits > 64 - bitBufferLength) {
				for (; bitBufferLength >= 8; bitBufferLength -= 8, bitBuffer >>>= 8)
					output.write((byte)bitBuffer);
			}
			assert numBits <= 64 - bitBufferLength;
			bitBuffer |= (long)value << bitBufferLength;
			bitBufferLength += numBits;
		}
		
		
		@Override public int getBitPosition() {
			return bitBufferLength % 8;
		}
		
		
		public void finish() throws IOException {
			writeBits(0, (8 - getBitPosition()) % 8);
			for (; bitBufferLength >= 8; bitBufferLength -= 8, bitBuffer >>>= 8)
				output.write((byte)bitBuffer);
			assert bitBufferLength == 0;
		}
		
	}
	
}
