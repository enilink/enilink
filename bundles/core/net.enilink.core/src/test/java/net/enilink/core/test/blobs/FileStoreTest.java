package net.enilink.core.test.blobs;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import net.enilink.core.blobs.FileStore;

import org.junit.Assert;
import org.junit.Test;

public class FileStoreTest {
	@Test
	public void simpleStoreTest() throws IOException {
		final Path root = Files.createTempDirectory("filestore-test");
		FileStore store = new FileStore(root);
		SecureRandom random = new SecureRandom();
		Charset charset = Charset.forName("UTF-8");

		List<String> keys = new ArrayList<>();
		for (int i = 0; i < 0xFF; i++) {
			byte[] origData = new BigInteger(2048, random).toString(16)
					.getBytes(charset);
			String key = store.store(origData, null);
			keys.add(key);
			try (DataInputStream in = new DataInputStream(store.openStream(key))) {
				byte[] data = new byte[origData.length];
				in.readFully(data);
				Assert.assertArrayEquals(origData, data);
			}
		}
		for (String key : keys) {
			// delete all files from the store
			store.delete(key);
		}
		try (DirectoryStream<?> ds = Files.newDirectoryStream(root)) {
			Assert.assertTrue("Store directory should be empty.", !ds
					.iterator().hasNext());
		}
		Files.delete(root);
	}
}
