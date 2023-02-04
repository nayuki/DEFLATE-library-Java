/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import io.nayuki.deflate.GzipInputStream;
import io.nayuki.deflate.GzipMetadata;
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
		
		try (var in = new GzipInputStream(new MarkableFileInputStream(inFile))) {
			{
				GzipMetadata meta = in.getMetadata();
				
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
			long elapsedTime = -System.nanoTime();
			try (OutputStream out = new FileOutputStream(outFile)) {
				in.transferTo(out);
			}
			elapsedTime += System.nanoTime();
			System.err.printf("Input  speed: %.2f MB/s%n",  inFile.length() / 1e6 / elapsedTime * 1.0e9);
			System.err.printf("Output speed: %.2f MB/s%n", outFile.length() / 1e6 / elapsedTime * 1.0e9);
			
		} catch (IOException e) {
			return "I/O exception: " + e.getMessage();
		}
		return null;
	}
	
}
