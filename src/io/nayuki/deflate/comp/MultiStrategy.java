/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate.comp;

import java.util.Objects;


public final class MultiStrategy implements Strategy {
	
	private Strategy[] strategies;
	
	
	public MultiStrategy(Strategy... strats) {
		Objects.requireNonNull(strats);
		for (Strategy st : strats)
			Objects.requireNonNull(st);
		if (strats.length == 0)
			throw new IllegalArgumentException("Empty list of strategies");
		strategies = strats;
	}
	
	
	public Decision decide(byte[] b, int off, int historyLen, int dataLen) {
		long minBitLength = Long.MAX_VALUE;
		Decision minDecision = null;
		for (Strategy st : strategies) {
			Decision dec = st.decide(b, off, historyLen, dataLen);
			long bitLength = dec.getBitLength();
			if (bitLength < minBitLength) {
				minBitLength = bitLength;
				minDecision = dec;
			}
		}
		return minDecision;
	}
	
}
