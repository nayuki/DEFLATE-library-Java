/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;


public record ZlibMetadata(
		CompressionMethod compressionMethod,
		int compressionInfo,  // Uint4
		Optional<Integer> presetDictionary,
		CompressionLevel compressionLevel) {
	
	
	/*---- Constructor ----*/
	
	public ZlibMetadata {
		Objects.requireNonNull(compressionMethod);
		
		if (compressionInfo >>> 4 != 0 || compressionMethod == CompressionMethod.DEFLATE && compressionInfo > 7)
			throw new IllegalArgumentException("Invalid compression info value");
		
		Objects.requireNonNull(presetDictionary);
		
		Objects.requireNonNull(compressionLevel);
	}
	
	
	public static final ZlibMetadata DEFAULT = new ZlibMetadata(
		CompressionMethod.DEFLATE, 7, Optional.empty(), CompressionLevel.DEFAULT);
	
	
	
	/*---- Static factory ----*/
	
	public static ZlibMetadata read(InputStream in) throws IOException {
		Objects.requireNonNull(in);
		int cmf = in.read();
		int flg = in.read();
		if (flg == -1)
			throw new EOFException("Unexpected end of stream");
		if ((cmf << 8 | flg) % CHECKSUM_MODULUS != 0)
			throw new DataFormatException("Header checksum mismatch");
		
		int compMethodInt = cmf & 0xF;
		CompressionMethod compMethod = switch (compMethodInt) {
			case  8 -> CompressionMethod.DEFLATE;
			case 15 -> CompressionMethod.RESERVED;
			default -> throw new DataFormatException("Unsupported compression method: " + compMethodInt);
		};
		
		int compInfo = cmf >>> 4;
		
		Optional<Integer> presetDict = Optional.empty();
		if (((flg >>> 5) & 1) != 0) {
			int val = 0;
			for (int i = 0; i < 4; i++) {
				int b = in.read();
				if (b == -1)
					throw new EOFException("Unexpected end of stream");
				val = (val << 8) | b;
			}
			presetDict = Optional.of(val);
		}
		
		CompressionLevel compLevel = CompressionLevel.values()[flg >>> 6];
		
		return new ZlibMetadata(compMethod, compInfo, presetDict, compLevel);
	}
	
	
	
	/*---- Method ----*/
	
	public void write(OutputStream out) throws IOException {
		Objects.requireNonNull(out);
		
		int compMethodInt = switch (compressionMethod) {
			case DEFLATE  -> 8;
			case RESERVED -> 15;
		};
		int cmf = (compMethodInt << 0) | (compressionInfo << 4);
		int flg = ((presetDictionary.isPresent() ? 1 : 0) << 5) | (compressionLevel.ordinal() << 6);
		flg |= (CHECKSUM_MODULUS - (cmf << 8 | flg) % CHECKSUM_MODULUS) % CHECKSUM_MODULUS;
		
		out.write(cmf);
		out.write(flg);
		if (presetDictionary.isPresent()) {
			int val = presetDictionary.get();
			for (int i = 3; i >= 0; i--)
				out.write(val >>> (i * 8));
		}
	}
	
	
	private static final int CHECKSUM_MODULUS = 31;
	
	
	
	/*---- Enumerations ----*/
	
	public enum CompressionMethod {
		DEFLATE,
		RESERVED,
	}
	
	
	public enum CompressionLevel {
		FASTEST,
		FAST,
		DEFAULT,
		MAXIMUM,
	}
	
}
