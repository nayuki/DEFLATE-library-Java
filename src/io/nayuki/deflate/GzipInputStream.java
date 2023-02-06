/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.zip.CRC32;
import io.nayuki.deflate.DataFormatException.Reason;


public final class GzipInputStream extends InputStream {
	
	/*---- Fields ----*/
	
	private InputStream rawInput;
	private InputStream decompressedInput;
	
	private final GzipMetadata metadata;
	
	private long decompressedLength = 0;
	private CRC32 checksum = new CRC32();
	
	
	
	/*---- Constructor ----*/
	
	public GzipInputStream(InputStream in) throws IOException {
		Objects.requireNonNull(in);
		metadata = GzipMetadata.read(in);
		if (!in.markSupported())
			in = new BufferedInputStream(in);
		rawInput = in;
		decompressedInput = new InflaterInputStream(in, true);
	}
	
	
	
	/*---- Methods ----*/
	
	public GzipMetadata getMetadata() {
		return metadata;
	}
	
	
	@Override public int read() throws IOException {
		var b = new byte[1];
		return switch (read(b)) {
			case  1 -> b[0] & 0xFF;
			case -1 -> -1;  // EOF
			default -> throw new AssertionError("Unreachable value");
		};
	}
	
	
	@Override public int read(byte[] b, int off, int len) throws IOException {
		if (decompressedInput == null)
			return -1;
		int result = decompressedInput.read(b, off, len);
		if (result != -1) {
			decompressedLength += result;
			checksum.update(b, off, result);
		} else {
			decompressedInput = null;
			int expectChecksum, expectLength;
			DataInput din = new DataInputStream(rawInput);
			try {
				expectChecksum = Integer.reverseBytes(din.readInt());
				expectLength = Integer.reverseBytes(din.readInt());
			} catch (EOFException e) {
				throw DataFormatException.throwUnexpectedEnd();
			}
			if ((int)checksum.getValue() != expectChecksum)
				throw new DataFormatException(Reason.DECOMPRESSED_CHECKSUM_MISMATCH, "Decompression CRC-32 mismatch");
			checksum = null;
			if ((int)decompressedLength != expectLength)
				throw new DataFormatException(Reason.DECOMPRESSED_SIZE_MISMATCH, "Decompressed size mismatch");
		}
		return result;
	}
	
	
	@Override public void close() throws IOException {
		rawInput.close();
		rawInput = null;
		decompressedInput = null;
		checksum = null;
	}
	
}
