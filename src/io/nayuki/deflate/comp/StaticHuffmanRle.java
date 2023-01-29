/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


public enum StaticHuffmanRle implements Strategy {
	
	SINGLETON;
	
	
	@Override public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		final long bitLength;
		{
			long bitLen = 1 + 2;  // bfinal, btype
			int index = off + historyLen;
			final int end = index + dataLen;
			while (index < end) {
				if (index > off) {
					byte prev = b[index - 1];
					int runLen = 0;
					for (; runLen < MAX_RUN_LENGTH && index + runLen < end && b[index + runLen] == prev; runLen++);
					if (runLen >= MIN_RUN_LENGTH) {
						int sym;
						if (runLen < 11) {
							sym = (runLen - 3) / 1 + 257;
							bitLen += 0;
						} else if (runLen < 19) {
							sym = (runLen - 11) / 2 + 265;
							bitLen += 1;
						} else if (runLen < 35) {
							sym = (runLen - 19) / 4 + 269;
							bitLen += 2;
						} else if (runLen < 67) {
							sym = (runLen - 35) / 8 + 273;
							bitLen += 3;
						} else if (runLen < 131) {
							sym = (runLen - 67) / 16 + 277;
							bitLen += 4;
						} else if (runLen < 258) {
							sym = (runLen - 131) / 32 + 281;
							bitLen += 5;
						} else if (runLen == 258) {
							sym = 285;
							bitLen += 0;
						} else
							throw new AssertionError("Unreachable value");
						bitLen += sym < 280 ? 7 : 8;  // Length code
						bitLen += 5;  // Distance code
						index += runLen;
						continue;
					}
				}
				bitLen += (b[index] & 0xFF) < 144 ? 8 : 9;
				index++;
			}
			bitLen += 7;
			bitLength = bitLen;
		}
		
		
		return new Decision() {
			@Override public long getBitLength() {
				return bitLength;
			}
			
			@Override public boolean containsUncompressedBlock() {
				return false;
			}
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				out.writeBits(isFinal ? 1 : 0, 1);
				out.writeBits(1, 2);
				
				int index = off + historyLen;
				final int end = index + dataLen;
				while (index < end) {
					if (index > off) {
						byte prev = b[index - 1];
						int runLen = 0;
						for (; runLen < MAX_RUN_LENGTH && index + runLen < end && b[index + runLen] == prev; runLen++);
						if (runLen >= MIN_RUN_LENGTH) {
							int sym, extra, numExtra;
							if (runLen < 11) {
								sym = (runLen - 3) / 1 + 257;
								extra = 0;
								numExtra = 0;
							} else if (runLen < 19) {
								sym = (runLen - 11) / 2 + 265;
								extra = (runLen - 11) % 2;
								numExtra = 1;
							} else if (runLen < 35) {
								sym = (runLen - 19) / 4 + 269;
								extra = (runLen - 19) % 4;
								numExtra = 2;
							} else if (runLen < 67) {
								sym = (runLen - 35) / 8 + 273;
								extra = (runLen - 35) % 8;
								numExtra = 3;
							} else if (runLen < 131) {
								sym = (runLen - 67) / 16 + 277;
								extra = (runLen - 67) % 16;
								numExtra = 4;
							} else if (runLen < 258) {
								sym = (runLen - 131) / 32 + 281;
								extra = (runLen - 131) % 32;
								numExtra = 5;
							} else if (runLen == 258) {
								sym = 285;
								extra = 0;
								numExtra = 0;
							} else
								throw new AssertionError("Unreachable value");
							out.writeBits(HUFFMAN_CODES[sym], (sym < 280 ? 7 : 8));  // Length code
							out.writeBits(extra, numExtra);
							out.writeBits(0, 5);  // Distance code
							index += runLen;
							continue;
						}
					}
					
					int sym = b[index] & 0xFF;
					out.writeBits(HUFFMAN_CODES[sym], (sym < 144 ? 8 : 9));
					index++;
				}
				out.writeBits(0, 7);  // Symbol 256 (end of block)
			}
		};
	}
	
	
	private static short[] HUFFMAN_CODES = new short[286];
	
	static {
		int i = 0;
		for (; i < 144; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i -   0 +  48) >>> (32 - 8));
		for (; i < 256; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 144 + 400) >>> (32 - 9));
		for (; i < 280; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 256 +   0) >>> (32 - 7));
		for (; i < 286; i++) HUFFMAN_CODES[i] = (short)(Integer.reverse(i - 280 + 192) >>> (32 - 8));
	}
	
	
	// Configurable
	private static final int MIN_RUN_LENGTH = 3;
	private static final int MAX_RUN_LENGTH = 258;
	static {
		assert 3 <= MIN_RUN_LENGTH && MIN_RUN_LENGTH <= MAX_RUN_LENGTH && MAX_RUN_LENGTH <= 258;
	}
	
}
