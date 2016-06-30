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
	
	// Buffer of bytes read from in.read() (the underlying input stream)
	private byte[] inputBuffer;     // Can have any positive length (but longer means less overhead)
	private int inputBufferLength;  // Number of valid prefix bytes, or -1 to indicate end of stream
	private int inputBufferIndex;   // Index of next byte to consume
	
	// Buffer of bits read from the bytes in 'inputBuffer'
	private long inputBitBuffer;       // 0 <= value < 2^inputBitBufferLength
	private int inputBitBufferLength;  // Always in the range [0, 63]
	
	// -3: A data format exception has been thrown.
	// -2: This inflater stream has been closed.
	// 0 to 65535: Number of bytes remaining in current uncompressed block.
	private int state;
	
	// Indicates whether a block header with the "bfinal" flag has been seen.
	private boolean isLastBlock;
	
	
	
	protected InflaterInputStream(InputStream in) {
		super(in);
		
		// Initialize data buffers
		inputBuffer = new byte[16 * 1024];
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
		
		// Initialize state
		state = 0;
		isLastBlock = false;
	}
	
	
	
	public int read() throws IOException {
		byte[] b = new byte[1];
		while (true) {
			switch (read(b)) {
				case 1:
					return (b[0] & 0xFF);
				case 0:
					continue;
				case -1:
					return -1;
				default:
					throw new AssertionError();
			}
		}
	}
	
	
	public int read(byte[] b, int off, int len) throws IOException {
		// Check state and arguments
		if (state == -2)
			throw new IllegalStateException("Stream already closed");
		if (state == -3)
			throw new IOException("The stream contained invalid data");
		if (off < 0 || off > b.length || len < 0 || b.length - off < len)
			throw new IndexOutOfBoundsException();
		
		// Get into a block
		while (state == 0) {
			if (isLastBlock)
				return -1;
			if (len == 0)
				return 0;
			
			isLastBlock = readBits(1) == 1;
			int type = readBits(2);
			if (type != 0)
				throw new UnsupportedOperationException("Only uncompressed blocks supported");
			
			alignInputToByte();
			state = readBits(16);  // Block length
			if (state != (readBits(16) ^ 0xFFFF))
				invalidData("len/nlen mismatch in uncompressed block");
		}
		
		assert 1 <= state && state <= 0xFFFF;
		int n = Math.min(state, len);
		readBytes(b, off, n);
		state -= n;
		return n;
	}
	
	
	public void close() throws IOException {
		state = -2;
		isLastBlock = true;
		super.close();
		
		// Clear buffers
		inputBuffer = null;
		inputBufferLength = 0;
		inputBufferIndex = 0;
		inputBitBuffer = 0;
		inputBitBufferLength = 0;
	}
	
	
	// Returns the given number of least significant bits from the bit buffer,
	// which updates the bit buffer and possibly also the byte buffer.
	private int readBits(int numBits) throws IOException {
		// Check arguments and invariants
		assert 1 <= numBits && numBits <= 16;  // Max value used in DEFLATE is 16, but this method is designed to be valid for numBits <= 31
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;  // Ensure high-order bits are clean
		
		// Ensure there is enough data in the bit buffer to satisfy the request
		byte[] b = inputBuffer;  // Shorter name
		while (inputBitBufferLength < numBits) {
			int i = inputBufferIndex;  // Shorter name
			
			// Pack as many bytes as possible from input byte buffer into the bit buffer
			int numBytes = Math.min((64 - inputBitBufferLength) >>> 3, inputBufferLength - i);
			long temp;  // Bytes packed in little endian
			if (numBytes == 8) {  // ~90% hit rate
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8 | (b[i+6]&0xFF)<<16 | b[i+7]<<24) << 32;
			} else if (numBytes == 7) {  // ~5% hit rate
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8 | (b[i+6]&0xFF)<<16) << 32;
			} else if (numBytes == 6) {
				temp =     (((b[i]&0xFF) | (b[i+1]&0xFF)<<8 | (b[i+2]&0xFF)<<16 | b[i+3]<<24) & 0xFFFFFFFFL) |
				    (long)((b[i+4]&0xFF) | (b[i+5]&0xFF)<<8) << 32;
			} else if (numBytes > 0) {
				// This slower general logic is valid for 1 <= bytes <= 8
				temp = 0;
				for (int j = 0; j < numBytes; i++, j++)
					temp |= (b[i] & 0xFFL) << (j << 3);
			} else if (numBytes == 0 && inputBufferLength != -1) {
				// Fill and retry
				fillInputBuffer();
				continue;
			} else
				throw new AssertionError();
			
			// Update the buffer
			inputBitBuffer |= temp << inputBitBufferLength;
			inputBitBufferLength += numBytes << 3;
			inputBufferIndex += numBytes;
			assert inputBitBufferLength <= 64;
		}
		
		// Extract bits to return
		int result = (int)inputBitBuffer & ((1 << numBits) - 1);
		inputBitBuffer >>>= numBits;
		inputBitBufferLength -= numBits;
		
		// Check return and recheck invariants
		assert result >>> numBits == 0;
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;
		return result;
	}
	
	
	private void readBytes(byte[] b, int off, int len) throws IOException {
		// Check bit buffer invariants
		assert 0 <= inputBitBufferLength && inputBitBufferLength <= 63;
		assert inputBitBuffer >>> inputBitBufferLength == 0;
		
		// Unpack saved bits first
		alignInputToByte();
		for (; len > 0 && inputBitBufferLength >= 8; off++, len--) {
			b[off] = (byte)inputBitBuffer;
			inputBitBuffer >>>= 8;
			inputBitBufferLength -= 8;
		}
		
		// Read from input buffer
		{
			int n = Math.min(len, inputBufferLength - inputBufferIndex);
			System.arraycopy(inputBuffer, inputBufferIndex, b, off, n);
			inputBufferIndex += n;
			off += n;
			len -= n;
		}
		
		// Read directly from input stream
		while (len > 0) {
			int n = in.read(b, off, len);
			if (n == -1) {
				inputBufferIndex = 0;
				inputBufferLength = -1;
				state = -3;
				isLastBlock = true;
				throw new EOFException();
			}
			off += n;
			len -= n;
		}
	}
	
	
	// Fills the input byte buffer with new data read from the underlying input stream.
	// Requires the buffer to be fully consumed before being called.
	// Sets inputBufferLength to a number in the range [-1, inputBuffer.length].
	private void fillInputBuffer() throws IOException {
		if (inputBufferIndex < inputBufferLength)
			throw new AssertionError("Input buffer not fully consumed yet");
		inputBufferLength = in.read(inputBuffer);
		inputBufferIndex = 0;
		if (inputBufferLength == -1) {
			state = -3;
			isLastBlock = true;
			throw new EOFException();
		}
		if (inputBufferLength < -1 || inputBufferLength > inputBuffer.length)
			throw new AssertionError();
	}
	
	
	// Discards the remaining bits (0 to 7) in the current byte being read, if any.
	private void alignInputToByte() {
		int discard = inputBitBufferLength & 7;
		inputBitBuffer >>>= discard;
		inputBitBufferLength -= discard;
		assert inputBitBufferLength % 8 == 0;
	}
	
	
	private void invalidData(String reason) throws IOException {
		state = -3;
		isLastBlock = true;
		throw new IOException("Invalid DEFLATE data: " + reason);
	}
	
}
