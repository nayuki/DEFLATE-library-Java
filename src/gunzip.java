/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.zip.CRC32;
import io.nayuki.deflate.GzipMetadata;
import io.nayuki.deflate.InflaterInputStream;
import io.nayuki.deflate.MarkableFileInputStream;


/**
 * Decompression application for the gzip file format.
 * <p>Usage: java gunzip InputFile.gz OutputFile</p>
 * <p>This decompresses a single gzip file into a single output file. The program also prints
 * some information to standard output, and error messages if the file is invalid/corrupt.</p>
 */
public final class gunzip {
	
	public static void main(String[] args) {
		String msg = submain(args);
		if (msg != null) {
			System.err.println(msg);
			System.exit(1);
		}
	}
	
	
	// Returns null if successful, otherwise returns an error message string.
	private static String submain(String[] args) {
		// Check arguments
		if (args.length != 2)
			return "Usage: java gunzip InputFile.gz OutputFile";
		
		var inFile = new File(args[0]);
		if (!inFile.exists())
			return "Input file does not exist: " + inFile;
		if (inFile.isDirectory())
			return "Input file is a directory: " + inFile;
		var outFile = new File(args[1]);
		
		try (var in = new MarkableFileInputStream(inFile)) {
			{
				GzipMetadata meta = GzipMetadata.read(in);
				
				System.err.println("Last modified: " + meta.modificationTimeUnixS()
					.map(t -> Instant.EPOCH.plusSeconds(t).toString()).orElse("N/A"));
				
				int extraFlags = meta.extraFlags();
				System.err.println("Extra flags: " + switch (extraFlags) {
					case 2  -> "Maximum compression";
					case 4  -> "Fastest compression";
					default -> "Unknown (" + extraFlags + ")";
				});
				
				System.err.println("Operating system: " + switch (meta.operatingSystem()) {
					case FAT_FILESYSTEM  -> "FAT filesystem";
					case AMIGA           -> "Amiga";
					case VMS             -> "VMS";
					case UNIX            -> "Unix";
					case VM_CMS          -> "VM/CMS";
					case ATARI_TOS       -> "Atari TOS";
					case HPFS_FILESYSTEM -> "HPFS filesystem";
					case MACINTOSH       -> "Macintosh";
					case Z_SYSTEM        -> "Z-System";
					case CPM             -> "CP/M";
					case TOPS_20         -> "TOPS-20";
					case NTFS_FILESYSTEM -> "NTFS filesystem";
					case QDOS            -> "QDOS";
					case ACORN_RISCOS    -> "Acorn RISCOS";
					case UNKNOWN         -> "Unknown";
					default              -> throw new AssertionError("Unreachable value");
				});
				
				System.err.println("File mode: " + (meta.isFileText() ? "Text" : "Binary"));
				
				meta.extraField().ifPresent(b ->
					System.err.println("Extra field: " + b.length + " bytes"));
				
				meta.fileName().ifPresent(s ->
					System.err.println("File name: " + s));
				
				meta.comment().ifPresent(s ->
					System.err.println("Comment: " + s));
			}
			
			// Start decompressing and writing output file
			try (OutputStream fout = new FileOutputStream(outFile)) {
				var lcout = new LengthCrc32OutputStream(fout);
				var iin = new InflaterInputStream(in, true);
				var buf = new byte[64 * 1024];
				long elapsedTime = -System.nanoTime();
				while (true) {
					int n = iin.read(buf);
					if (n == -1)
						break;
					lcout.write(buf, 0, n);
				}
				elapsedTime += System.nanoTime();
				System.err.printf("Input  speed: %.2f MB/s%n",  inFile.length() / 1e6 / elapsedTime * 1.0e9);
				System.err.printf("Output speed: %.2f MB/s%n", outFile.length() / 1e6 / elapsedTime * 1.0e9);
				
				// Process gzip footer
				DataInput din = new DataInputStream(in);
				if (lcout.getCrc32() != readLittleEndianInt32(din))
					return "Decompression CRC-32 mismatch";
				if ((int)lcout.getLength() != readLittleEndianInt32(din))
					return "Decompressed size mismatch";
			}
		} catch (IOException e) {
			return "I/O exception: " + e.getMessage();
		}
		return null;
	}
	
	
	
	/*---- Helper methods and class ----*/
	
	private static int readLittleEndianInt32(DataInput in) throws IOException {
		return Integer.reverseBytes(in.readInt());
	}
	
	
	
	private static final class LengthCrc32OutputStream extends FilterOutputStream {
		
		private long length;  // Total number of bytes written, modulo 2^64
		private CRC32 checksum;
		
		
		public LengthCrc32OutputStream(OutputStream out) {
			super(out);
			length = 0;
			checksum = new CRC32();
		}
		
		
		@Override public void write(int b) throws IOException {
			out.write(b);
			length++;
			checksum.update(b);
		}
		
		
		@Override public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			length += len;
			checksum.update(b, off, len);
		}
		
		
		public long getLength() {
			return length;
		}
		
		
		public int getCrc32() {
			return (int)checksum.getValue();
		}
		
	}
	
}
