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
		return new Decision() {
			private final long[] bitLengths = new long[8];
			private final Decision[] subdecisions = new Decision[bitLengths.length];
			{
				Arrays.fill(bitLengths, Long.MAX_VALUE);
				for (Strategy st : strategies) {
					Decision dec = st.decide(b, off, historyLen, dataLen);
					long[] bitLens = dec.getBitLengths();
					for (int i = 0; i < bitLengths.length; i++) {
						if (bitLens[i] < bitLengths[i]) {
							bitLengths[i] = bitLens[i];
							subdecisions[i] = dec;
						}
					}
				}
				for (Decision dec : subdecisions)
					Objects.requireNonNull(dec);
			}
			
			@Override public long[] getBitLengths() {
				return bitLengths;
			}
			
			@Override public void compressTo(BitOutputStream out, boolean isFinal) throws IOException {
				subdecisions[out.getBitPosition()].compressTo(out, isFinal);
			}
		};
	}
	
}
