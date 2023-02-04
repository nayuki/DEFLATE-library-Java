/* 
 * DEFLATE library (Java)
 * 
 * Copyright (c) Project Nayuki
 * MIT License. See readme file.
 * https://www.nayuki.io/page/deflate-library-java
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import io.nayuki.deflate.GzipMetadata;
import io.nayuki.deflate.GzipOutputStream;


/**
 * Compression application for the gzip file format.
 * <p>Usage: java gzip InputFile OutputFile.gz</p>
 * <p>This compresses a single input file into a single gzip output file.</p>
 */
public final class gzip {
	
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
			return "Usage: java gzip InputFile OutputFile.gz";
		
		var inFile = new File(args[0]);
		if (!inFile.exists())
			return "Input path does not exist: " + inFile;
		if (inFile.isDirectory())
			return "Input path is a directory: " + inFile;
		var outFile = new File(args[1]);
		if (outFile.isDirectory())
			return "Output path is a directory: " + outFile;
		
		// Create the metadata structure
		int modTime = (int)(inFile.lastModified() / 1000);
		var meta = new GzipMetadata(
			GzipMetadata.CompressionMethod.DEFLATE,
			false,
			modTime != 0 ? Optional.of(modTime) : Optional.empty(),
			0,
			GzipMetadata.OperatingSystem.UNIX,
			Optional.empty(),
			Optional.of(inFile.getName()),
			Optional.empty(),
			true);
		
		// Start compressing and writing output file
		long elapsedTime = -System.nanoTime();
		try (InputStream in = new FileInputStream(inFile);
				OutputStream out = new GzipOutputStream(new FileOutputStream(outFile), meta)) {
			in.transferTo(out);
		} catch (IOException e) {
			return "I/O exception: " + e.getMessage();
		}
		elapsedTime += System.nanoTime();
		System.err.printf("Input  speed: %.2f MB/s%n",  inFile.length() / 1e6 / elapsedTime * 1.0e9);
		System.err.printf("Output speed: %.2f MB/s%n", outFile.length() / 1e6 / elapsedTime * 1.0e9);
		
		return null;
	}
	
}
