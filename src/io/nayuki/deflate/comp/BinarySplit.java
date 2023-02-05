/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;


public final class BinarySplit implements Strategy {
	
	private final Strategy substrategy;
	private final int minimumBlockLength;
	
	
	public BinarySplit(Strategy strat, int minBlockLen) {
		substrategy = Objects.requireNonNull(strat);
		if (minBlockLen < 1)
			throw new IllegalArgumentException("Non-positive minimum block length");
		minimumBlockLength = minBlockLen;
	}
	
	
	public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		return decide(b, off, historyLen, dataLen,
			substrategy.decide(b, off, historyLen, dataLen));
	}
	
	
	private Decision decide(byte[] b, int off, int historyLen, int dataLen, Decision curDec) {
		var subdecisions = new Decision[8][];
		Arrays.fill(subdecisions, new Decision[]{curDec});
		long[] bitLengths = curDec.getBitLengths().clone();
		
		int firstHalfLen = (dataLen + 1) / 2;
		int secondHalfLen = dataLen - firstHalfLen;
		if (Math.min(firstHalfLen, secondHalfLen) > minimumBlockLength) {
			Decision[] splitDecs = {
				substrategy.decide(b, off, historyLen, firstHalfLen),
				substrategy.decide(b, off, historyLen + firstHalfLen, secondHalfLen),
			};
			boolean improved = false;
			for (int i = 0; i < bitLengths.length; i++) {
				long bitLen = 0;
				for (Decision dec : splitDecs)
					bitLen += dec.getBitLengths()[(int)(bitLen % 8)];
				improved |= bitLen < bitLengths[i];
			}
			
			if (improved) {
				splitDecs[0] = decide(b, off, historyLen, firstHalfLen, splitDecs[0]);
				splitDecs[1] = decide(b, off, historyLen + firstHalfLen, secondHalfLen, splitDecs[1]);
			}
			for (int i = 0; i < bitLengths.length; i++) {
				long bitLen = 0;
				for (Decision dec : splitDecs)
					bitLen += dec.getBitLengths()[(int)(bitLen % 8)];
				if (bitLen < bitLengths[i]) {
					bitLengths[i] = bitLen;
					subdecisions[i] = splitDecs;
				}
			}
		}
		
		return new Decision() {
			@Override public long[] getBitLengths() {
				return bitLengths;
			}
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				Decision[] decs = subdecisions[out.getBitPosition()];
				for (int i = 0; i < decs.length; i++)
					decs[i].compressTo(out, isFinal && i == decs.length - 1);
			}
		};
	}
	
}
