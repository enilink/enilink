package net.enilink.platform.core.blobs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Hex;

/**
 * A simple file store that stores blobs in a directory layout comparable to
 * GIT's objects folder.
 */
public class FileStore {
	private static final int DIR_LEVELS = 3;
	private static final Pattern KEY_PATTERN = Pattern
			.compile("(md5|sha1|sha256)-([0-9a-f]{32,})");

	protected Path root;

	public FileStore(Path root) {
		this.root = root;
	}

	protected String computeKey(InputStream in, long length) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
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
		return new StringBuilder("sha1").append("-")
				.append(Hex.encodeHex(digest.digest())).toString();
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
	 * Return a properties object for the file associated with the given
	 * <code>key</code>.
	 * 
	 * @param key
	 *            Key of the file.
	 * @return A properties object or <code>null</code> if it does not exist.
	 */
	public Properties getProperties(String key) {
		Path metaPath = metaDataPath(pathForKey(key));
		Properties properties = new Properties();
		if (Files.exists(metaPath)) {
			try {
				properties.load(new BufferedInputStream(Files
						.newInputStream(metaPath)));
			} catch (IOException e) {
				// ignore
			}
		}
		return properties;
	}

	/**
	 * Returns the path of the properties file with meta-data about the given
	 * file denoted by <code>path</code>.
	 * 
	 * @param path
	 *            The path which should be described with meta-data.
	 * @return The path of a properties file.
	 */
	protected Path metaDataPath(Path path) {
		return path.resolveSibling(path.getFileName().toString()
				+ ".properties");
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
	public InputStream openStream(String key) throws IOException {
		return new BufferedInputStream(Files.newInputStream(pathForKey(key)));
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
		Matcher m = KEY_PATTERN.matcher(key);
		if (!m.matches()) {
			throw new IllegalArgumentException("Invalid key: " + key);
		}
		// split key into 3 folders and one file name
		Path dir = root.resolve(m.group(1));
		String hashSuffix = m.group(2);
		for (int i = 0; i < DIR_LEVELS - 1; i++) {
			int start = i * 2;
			dir = dir.resolve(hashSuffix.substring(start, start + 2));
		}
		return dir.resolve(key);
	}

	/**
	 * Store meta-data for a object denoted by <code>key</code>.
	 * 
	 * @param key
	 *            The key of a stored object.
	 * @param properties
	 *            Meta-data for a stored object.
	 * @throws IOException
	 *             If meta-data could not be stored to a file.
	 */
	public void setProperties(String key, Properties properties)
			throws IOException {
		Path metaPath = metaDataPath(pathForKey(key));
		// override with existing properties
		properties.putAll(getProperties(key));
		properties.store(
				new BufferedOutputStream(Files.newOutputStream(metaPath)), "");
	}

	/**
	 * Return the size of the file associated with the given <code>key</code>.
	 * 
	 * @param key
	 *            Key of the file.
	 * @return The size of the file or <code>0</code> if it does not exists.
	 */
	public long size(String key) throws IOException {
		return Files.size(pathForKey(key));
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
	public String store(byte[] data) throws IOException {
		String key;
		try {
			key = computeKey(new ByteArrayInputStream(data), data.length);
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
		try {
			key = computeKey(Files.newInputStream(file), Files.size(file));
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