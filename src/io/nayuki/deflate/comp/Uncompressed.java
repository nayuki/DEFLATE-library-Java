/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;


public enum Uncompressed implements Strategy {
	
	SINGLETON;
	
	
	@Override public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		return new Decision() {
			private final long[] bitLengths = new long[8];
			{
				int numBlocks = Math.max(Math.ceilDiv(dataLen, MAX_BLOCK_LEN), 1);
				for (int i = 0; i < bitLengths.length; i++)
					bitLengths[i] = dataLen * 8L + numBlocks * 40L + ((13 - i) % 8 - 5);
			}
			
			@Override public long[] getBitLengths() {
				return bitLengths;
			}
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				int index = off + historyLen;
				final int end = index + dataLen;
				do {
					int n = Math.min(end - index, MAX_BLOCK_LEN);
					out.writeBits((isFinal && n == end - index) ? 1 : 0, 1);
					out.writeBits(0, 2);
					out.writeBits(0, (8 - out.getBitPosition()) % 8);
					out.writeBits(n ^ 0x0000, 16);
					out.writeBits(n ^ 0xFFFF, 16);
					int e = index + n;
					for (; index < e; index++)
						out.writeBits(b[index] & 0xFF, 8);
				} while (index < end);
			}
		};
	}
	
	
	private static final int MAX_BLOCK_LEN = (1 << 16) - 1;  // Configurable in the range [1, 65535]
	
}
