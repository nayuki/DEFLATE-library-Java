/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

package io.nayuki.deflate;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;


public record GzipMetadata(
		CompressionMethod compressionMethod,
		boolean isFileText,
		Optional<Integer> modificationTimeUnixS,
		int extraFlags,
		OperatingSystem operatingSystem,
		Optional<byte[]> extraField,
		Optional<String> fileName,
		Optional<String> comment,
		boolean hasHeaderCrc) {
	
	
	/*---- Constructor ----*/
	
	public GzipMetadata {
		Objects.requireNonNull(compressionMethod);
		
		Objects.requireNonNull(modificationTimeUnixS);
		modificationTimeUnixS.ifPresent(x -> {
			if (x == 0)
				throw new IllegalArgumentException("Modification timestamp is zero");
		});
		
		if (extraFlags >>> 8 != 0)
			throw new IllegalArgumentException("Invalid extra flags value");
		
		Objects.requireNonNull(operatingSystem);
		
		Objects.requireNonNull(extraField);
		extraField.ifPresent(b -> {
			if (b.length > 0xFFFF)
				throw new IllegalArgumentException("Extra field too long");
		});
		
		Objects.requireNonNull(fileName);
		
		Objects.requireNonNull(comment);
	}
	
	
	
	/*---- Static factory ----*/
	
	public static GzipMetadata read(InputStream in) throws IOException {
		Objects.requireNonNull(in);
		var in1 = new CheckedInputStream(in, new CRC32());
		DataInput in2 = new DataInputStream(in1);
		
		// -- Read and process 10-byte header --
		if (in2.readUnsignedShort() != 0x1F8B)
			throw new DataFormatException("Invalid GZIP magic number");
		
		int compMethodInt = in2.readUnsignedByte();
		if (compMethodInt != 8)
			throw new DataFormatException("Unsupported compression method: " + compMethodInt);
		CompressionMethod compMethod = CompressionMethod.DEFLATE;
		
		var flagByte = new byte[1];
		in2.readFully(flagByte);
		BitSet flags = BitSet.valueOf(flagByte);
		
		// Reserved flags
		if (flags.get(5) || flags.get(6) || flags.get(7))
			throw new DataFormatException("Reserved flags are set");
		
		// Modification time
		int modTimeInt = Integer.reverseBytes(in2.readInt());
		Optional<Integer> modTime = modTimeInt != 0 ? Optional.of(modTimeInt) : Optional.empty();
		
		// Extra flags
		int extraFlags = in2.readUnsignedByte();
		
		// Operating system
		int operSystemInt = in2.readUnsignedByte();
		OperatingSystem operSystem;
		if (operSystemInt < OperatingSystem.UNKNOWN.ordinal())
			operSystem = OperatingSystem.values()[operSystemInt];
		else if (operSystemInt == 0xFF)
			operSystem = OperatingSystem.UNKNOWN;
		else
			throw new DataFormatException("Unsupported operating system value");
		
		// -- Handle assorted flags and read more data --
		boolean isFileText = flags.get(0);
		
		Optional<byte[]> extraField = Optional.empty();
		if (flags.get(2)) {
			int len = Integer.reverseBytes(in2.readShort()) >>> 16;
			var b = new byte[len];
			in2.readFully(b);
			extraField = Optional.of(b);
		}
		
		Optional<String> fileName = Optional.empty();
		if (flags.get(3))
			fileName = Optional.of(readNullTerminatedString(in2));
		
		Optional<String> comment = Optional.empty();
		if (flags.get(4))
			comment = Optional.of(readNullTerminatedString(in2));
		
		boolean hasHeaderCrc = flags.get(1);
		if (hasHeaderCrc) {
			int expect = (int)in1.getChecksum().getValue() & 0xFFFF;
			int actual = Integer.reverseBytes(in2.readShort()) >>> 16;
			if (actual != expect)
				throw new DataFormatException("Header CRC-16 mismatch");
		}
		
		return new GzipMetadata(compMethod, isFileText, modTime, extraFlags,
			operSystem, extraField, fileName, comment, hasHeaderCrc);
	}
	
	
	private static String readNullTerminatedString(DataInput in) throws IOException {
		var bout = new ByteArrayOutputStream();
		while (true) {
			byte b = in.readByte();
			if (b == 0)
				break;
			bout.write(b);
		}
		return new String(bout.toByteArray(), StandardCharsets.ISO_8859_1);
	}
	
	
	
	/*---- Enumerations ----*/
	
	public enum CompressionMethod {
		DEFLATE,
	}
	
	
	public enum OperatingSystem {
		FAT_FILESYSTEM,
		AMIGA,
		VMS,
		UNIX,
		VM_CMS,
		ATARI_TOS,
		HPFS_FILESYSTEM,
		MACINTOSH,
		Z_SYSTEM,
		CPM,
		TOPS_20,
		NTFS_FILESYSTEM,
		QDOS,
		ACORN_RISCOS,
		
		UNKNOWN,
	}
	
}
