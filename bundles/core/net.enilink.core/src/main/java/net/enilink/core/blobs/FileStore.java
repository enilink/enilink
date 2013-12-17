package net.enilink.core.blobs;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Hex;

import net.enilink.komma.core.URIImpl;

/**
 * A simple file store that stores file in a directory layout compared to GIT's
 * objects folder.
 */
public class FileStore {
	private static final int DIR_LEVELS = 2;
	private static final Pattern KEY_PATTERN = Pattern.compile("[0-9a-f]{40}");

	protected String computeKey(InputStream in, long length, String extension)
			throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		// add length to digest
		digest.update(("length: " + length).getBytes(Charset.forName("UTF-8")));
		try {
			int n = 0;
			byte[] buffer = new byte[8192];
			while (n != -1) {
				n = in.read(buffer);
				if (n > 0) {
					digest.update(buffer, 0, n);
				}
			}
		} finally {
			in.close();
		}
		return new String(Hex.encodeHex(digest.digest()))
				+ (extension == null ? "" : "." + extension).toLowerCase();
	}

	protected Path root;

	public FileStore(Path root) {
		this.root = root;
	}

	/**
	 * Deletes the file associated with the given <code>key</code>.
	 * 
	 * @param key
	 *            Key of the file.
	 * @return <code>true</code> if deletion was successful, else
	 *         <code>false</code>.
	 */
	public boolean delete(String key) throws IOException {
		Path path = pathForKey(key);
		if (Files.isRegularFile(path) && Files.deleteIfExists(path)) {
			for (int i = 0; i < DIR_LEVELS; i++) {
				path = path.getParent();
				DirectoryStream<?> ds = Files.newDirectoryStream(path);
				boolean nonempty = ds.iterator().hasNext();
				ds.close();
				if (nonempty) {
					break;
				}
				Files.delete(path);
			}
			return true;
		}
		return false;
	}

	/**
	 * Test if file with <code>key</code> exists in this store.
	 * 
	 * @param key
	 *            Key of the file.
	 * @return <code>true</code> if file exists, else <code>false</code>.
	 */
	public boolean exists(String key) {
		return Files.exists(pathForKey(key));
	}

	/**
	 * Returns a path object for storing contents associated with the given
	 * <code>key</code>.
	 * 
	 * @param key
	 *            The key for some content.
	 * @return A file object for storing the content.
	 */
	protected Path pathForKey(String key) {
		if (!KEY_PATTERN.matcher(key).matches()) {
			throw new IllegalArgumentException("Invalid key: " + key);
		}
		Path dir = root;
		// split key into 2 folders and one file name
		for (int i = 0; i < 2; i++) {
			dir = dir.resolve(key.substring(0, 2));
			key = key.substring(2);
		}
		return dir.resolve(key);
	}

	/**
	 * Open an input stream for the file associated with the given
	 * <code>key</code>.
	 * 
	 * @param key
	 *            Key of the file.
	 * @return An input stream for the file or <code>null</code> if it does not
	 *         exist or an error has occurred.
	 */
	public InputStream openStream(String key) {
		Path path = pathForKey(key);
		try {
			return new BufferedInputStream(Files.newInputStream(path));
		} catch (IOException e) {
			// return null if file is not found
			return null;
		}
	}

	/**
	 * Stores a data array.
	 * 
	 * @param data
	 *            The data that should be stored.
	 * @param extension
	 *            The file extension for the data.
	 * @return A key for the stored file.
	 */
	public String store(byte[] data, String extension) throws IOException {
		String key;
		try {
			key = computeKey(new ByteArrayInputStream(data), data.length,
					extension);
		} catch (Exception e) {
			throw new IOException("Unable to compute hash for data.", e);
		}
		Path target = pathForKey(key);
		if (Files.exists(target)) {
			// assume that contents are identical
			return key;
		}
		Files.createDirectories(target.getParent());
		try (OutputStream os = Files.newOutputStream(target)) {
			os.write(data, 0, data.length);
		}
		return key;
	}

	/**
	 * Stores the contents of a file.
	 * 
	 * @param file
	 *            The file whose contents should be stored.
	 * @param move
	 *            <code>true</code> if original file should be moved, else
	 *            <code>false</code>.
	 * @return A key for the stored file.
	 */
	public String store(Path file, boolean move) throws IOException {
		String key;
		String extension = URIImpl.createFileURI(file.toString())
				.fileExtension();
		try {
			key = computeKey(Files.newInputStream(file), Files.size(file),
					extension);
		} catch (Exception e) {
			throw new IOException("Unable to compute hash for file: " + file, e);
		}
		Path target = pathForKey(key);
		if (Files.exists(target)) {
			// assume that contents are identical
			return key;
		}
		Files.createDirectories(target.getParent());
		if (move) {
			try {
				Files.move(file, target);
				return key;
			} catch (Exception e) {
				// ignore and try to copy file
			}
		}
		Files.copy(file, target);
		return key;
	}
}