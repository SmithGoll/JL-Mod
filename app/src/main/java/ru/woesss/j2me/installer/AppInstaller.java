/*
 * Copyright 2020-2024 Yury Kharchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.woesss.j2me.installer;

import android.net.Uri;
import android.util.Log;

import com.android.dx.command.dexer.Main;

import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.FileHeader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarFile;

import io.reactivex.SingleEmitter;
import ru.playsoftware.j2meloader.EmulatorApplication;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.ConverterException;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.IOUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;
import ru.woesss.util.zip.ZipFile;

public class AppInstaller {
	private static final String TAG = AppInstaller.class.getSimpleName();
	static final int STATUS_OLDER = -1;
	static final int STATUS_EQUAL = 0;
	static final int STATUS_NEWER = 1;
	static final int STATUS_NEW = 2;
	static final int STATUS_UNMATCHED = 3;
	static final int STATUS_SUCCESS = 4;
	static final int STATUS_SAME = 5;

	private final int id;
	private final AppListModel appListModel;
	private final File cacheDir = new File(EmulatorApplication.getInstance().getCacheDir(), "installer");

	private Uri uri;
	private Descriptor manifest;
	private Descriptor newDesc;
	private String appDirName;
	private File targetDir;
	private File srcJar;
	private File tmpDir;
	private AppItem currentApp;
	private File srcFile;

	AppInstaller(String path, Uri uri, AppListModel appListModel) {
		id = -1;
		this.appListModel = appListModel;
		if (path != null) srcFile = new File(path);
		this.uri = uri;
	}

	public AppInstaller(int id, AppListModel appListModel) {
		this.id = id;
		this.appListModel = appListModel;
	}

	Descriptor getNewDescriptor() {
		return newDesc;
	}

	String getCurrentVersion() {
		return currentApp.getVersion();
	}

	Descriptor getManifest() {
		return manifest;
	}

	/** Load and check app info from source */
	void loadInfo(SingleEmitter<Integer> emitter) throws IOException, ConverterException {
		if (id != -1) {
			currentApp = appListModel.getApp(id);
			srcJar = new File(currentApp.getPathExt(), Config.MIDLET_RES_FILE);
			newDesc = new Descriptor(new File(currentApp.getPathExt(), Config.MIDLET_MANIFEST_FILE), false);
			appDirName = currentApp.getPath();
			targetDir = new File(Config.getAppDir(), appDirName);
			emitter.onSuccess(STATUS_EQUAL);
			return;
		}
		boolean isLocal;
		if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
			downloadJad();
			isLocal = false;
		} else {
			srcFile = FileUtils.getFileForUri(uri);
			isLocal = true;
		}

		String name = srcFile.getName();

		if (name.toLowerCase().endsWith(".jad")) {
			newDesc = new Descriptor(srcFile, true);
			String url = newDesc.getJarUrl();
			if (url == null) {
				throw new ConverterException("Jad not have " + Descriptor.MIDLET_JAR_URL);
			}
			Uri uri = Uri.parse(url);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (isLocal && scheme == null && host == null) {
				if (!checkJarFile(srcFile)) {
					emitter.onSuccess(STATUS_UNMATCHED);
					return;
				}
			}
		} else if (name.toLowerCase().endsWith(".kjx")) {
			// Load kjx file
			parseKjx();
			newDesc = new Descriptor(srcFile, true);
		} else {
			srcJar = srcFile;
			newDesc = loadManifest(srcFile);
		}
		int result = checkDescriptor();
		emitter.onSuccess(result);
	}

	private void parseKjx() throws ConverterException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		try (DataInputStream dis = new DataInputStream(new FileInputStream(srcFile))) {
			byte[] magic = new byte[3];
			dis.readFully(magic, 0, 3);
			if (!Arrays.equals(magic, "KJX".getBytes())) {
				throw new ConverterException("Magic KJX does not match: " + new String(magic));
			}

			/*byte startJadPos = */dis.readByte();
			byte lenKjxFileName = dis.readByte();
			dis.skipBytes(lenKjxFileName);
			int lenJadFileContent = dis.readUnsignedShort();
			byte lenJadFileName = dis.readByte();
			byte[] jadFileName = new byte[lenJadFileName];
			dis.readFully(jadFileName, 0, lenJadFileName);
			String strJadFileName = new String(jadFileName);

			int bufSize = 2048;
			byte[] buf = new byte[bufSize];

			File jadFile = new File(cacheDir, strJadFileName);
			try (FileOutputStream fos = new FileOutputStream(jadFile)) {
				int restSize = lenJadFileContent;
				while (restSize > 0) {
					int readSize = dis.read(buf, 0, Math.min(restSize, bufSize));
					fos.write(buf, 0, readSize);
					restSize -= readSize;
				}
			}

			File jarFile = new File(cacheDir, strJadFileName.substring(0, strJadFileName.length() - 4) + ".jar");
			try (FileOutputStream fos = new FileOutputStream(jarFile)) {
				int length;
				while ((length = dis.read(buf)) > 0) {
					fos.write(buf, 0, length);
				}
			}

			srcFile = jadFile;
			srcJar = jarFile;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void downloadJad() throws ConverterException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		srcFile = new File(cacheDir, "tmp.jad");
		String url = uri.toString();
		Log.d(TAG, "Downloading " + url);
		Exception exception;
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setReadTimeout(3 * 60 * 1000);
			connection.setConnectTimeout(15000);
			int code = connection.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM
					|| code == HttpURLConnection.HTTP_MOVED_TEMP) {
				String urlStr = connection.getHeaderField("Location");
				connection.disconnect();
				connection = (HttpURLConnection) new URL(urlStr).openConnection();
				connection.setInstanceFollowRedirects(true);
				connection.setReadTimeout(3 * 60 * 1000);
				connection.setConnectTimeout(15000);
			}
			try (InputStream inputStream = connection.getInputStream();
				 OutputStream outputStream = new FileOutputStream(srcFile)) {
				byte[] buffer = new byte[2048];
				int length;
				while ((length = inputStream.read(buffer)) > 0) {
					outputStream.write(buffer, 0, length);
				}
			}
			connection.disconnect();
			Log.d(TAG, "Download complete");
			return;
		} catch (MalformedURLException e) {
			exception = e;
		} catch (FileNotFoundException e) {
			exception = e;
		} catch (IOException e) {
			exception = e;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
		deleteTemp();
		throw new ConverterException("Can't download jad", exception);
	}

	/** Install app */
	void install(SingleEmitter<Integer> emitter) throws ConverterException, IOException {
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new ConverterException("Can't create cache dir");
		}
		tmpDir = new File(targetDir.getParent(), ".tmp");
		if (!tmpDir.isDirectory() && !tmpDir.mkdirs())
			throw new ConverterException("Can't create directory: '" + targetDir + "'");
		if (srcJar == null) {
			srcJar = new File(cacheDir, "tmp.jar");
			downloadJar();
			manifest = loadManifest(srcJar);
			if (!manifest.equals(newDesc)) {
				emitter.onSuccess(STATUS_UNMATCHED);
				return;
			}
		}
		try {
			Main.main(new String[]{"--no-optimize",
					"--output=" + tmpDir + Config.MIDLET_DEX_ARCH,
					srcJar.getAbsolutePath()});
		} catch (Throwable e) {
			throw new ConverterException("Dexing error", e);
		}
		if (manifest != null) {
			manifest.merge(newDesc);
			newDesc = manifest;
		}
		File resJar = new File(tmpDir, Config.MIDLET_RES_FILE);
		FileUtils.copyFileUsingChannel(srcJar, resJar);
		String icon = newDesc.getIcon();
		File iconFile = new File(tmpDir, Config.MIDLET_ICON_FILE);
		if (icon != null) {
			try {
				ZipUtils.unzipEntry(resJar, icon, iconFile);
			} catch (IOException e) {
				Log.w(TAG, "Can't unzip icon: " + icon, e);
				//noinspection ResultOfMethodCallIgnored
				iconFile.delete();
			}
		}
		newDesc.writeTo(new File(tmpDir, Config.MIDLET_MANIFEST_FILE));
		FileUtils.deleteDirectory(targetDir);
		if (!tmpDir.renameTo(targetDir)) {
			throw new ConverterException("Can't move '" + tmpDir + "' to '" + targetDir + "'");
		}
		String name = newDesc.getName();
		String vendor = newDesc.getVendor();
		AppItem app = new AppItem(appDirName, name, vendor, newDesc.getVersion());
		if (currentApp != null) {
			app.setId(currentApp.getId());
			app.setTitle(currentApp.getTitle());
			String path = currentApp.getPath();
			if (!path.equals(appDirName)) {
				File rms = new File(Config.getDataDir(), path);
				if (rms.exists()) {
					File newRms = new File(Config.getDataDir(), appDirName);
					FileUtils.deleteDirectory(newRms);
					rms.renameTo(newRms);
				}
				File config = new File(Config.getConfigsDir(), path);
				if (config.exists()) {
					File newConfig = new File(Config.getConfigsDir(), appDirName);
					FileUtils.deleteDirectory(newConfig);
					config.renameTo(newConfig);
				}
				File appDir = new File(Config.getAppDir(), path);
				FileUtils.deleteDirectory(appDir);
			}
		}
		currentApp = app;
		appListModel.addApp(app);
		clearCache();
		deleteTemp();
		emitter.onSuccess(STATUS_SUCCESS);
	}

	private Descriptor loadManifest(File jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			FileHeader manifest = zip.getFileHeader(JarFile.MANIFEST_NAME);
			if (manifest == null) {
				throw new IOException("JAR not have " + JarFile.MANIFEST_NAME);
			}
			try (ZipInputStream is = zip.getInputStream(manifest)) {
				String text = new String(IOUtils.toByteArray(is));
				return new Descriptor(text, false);
			}
		}
	}

	/** return true if JAR exists and matches JAD **/
	private boolean checkJarFile(File jad) throws IOException, ConverterException {
		File dir = jad.getParentFile();
		String jarUrl = newDesc.getJarUrl();
		File jar = new File(dir, jarUrl);
		if (!jar.exists()) {
			String name = jad.getName();
			jar = new File(dir, name.substring(0, name.length() - 4) + ".jar");
			if (!jar.exists()) {
				throw new ConverterException("Jar-file not found for url: " + jarUrl);
			}
		}
		srcJar = jar;
		manifest = loadManifest(jar);
		return manifest.equals(newDesc);
	}

	private int checkDescriptor() {
		// Remove invalid characters from app path
		String name = newDesc.getName();
		String vendor = newDesc.getVendor();
		currentApp = appListModel.getApp(name, vendor);
		if (currentApp == null) {
			generatePathName(name.replaceAll(FileUtils.ILLEGAL_FILENAME_CHARS, "").trim());
			return STATUS_NEW;
		}
		appDirName = currentApp.getPath();
		targetDir = new File(Config.getAppDir(), appDirName);
		int result = newDesc.compareVersion(currentApp.getVersion());
		if (result != 0) {
			return result;
		}
		if (srcJar == null || !srcJar.exists()) {
			return STATUS_EQUAL;
		}
		try {
			Descriptor oldDesc = new Descriptor(new File(targetDir, Config.MIDLET_MANIFEST_FILE), false);
			if (!oldDesc.containsAllAttributes(newDesc)) {
				return STATUS_EQUAL;
			}
		} catch (IOException e) {
			Log.e(TAG, "checkDescriptor: error read exists app manifest", e);
		}
		File targetJar = new File(targetDir, Config.MIDLET_RES_FILE);
		if (targetJar.exists() && targetJar.length() == srcJar.length()) {
			try (FileInputStream one = new FileInputStream(srcJar);
				 FileInputStream two = new FileInputStream(targetJar)) {
				if (one.read() != two.read()) {
					return STATUS_EQUAL;
				}
				return STATUS_SAME;
			} catch (IOException e) {
				Log.e(TAG, "checkDescriptor: io error when compare files", e);
			}
		}
		return STATUS_EQUAL;
	}

	private void generatePathName(String name) {
		String appsDir = Config.getAppDir();
		File dir = new File(appsDir, name);
		for (int i = 1; dir.exists(); i++) {
			dir = new File(appsDir, name + "_" + i);
		}
		appDirName = dir.getName();
		targetDir = dir;
	}

	private void downloadJar() throws ConverterException {
		Uri jarUri = Uri.parse(newDesc.getJarUrl());
		if (jarUri.getScheme() == null) {
			String schemeOfJadSource = this.uri.getScheme();
			if ("http".equals(schemeOfJadSource) || "https".equals(schemeOfJadSource)) {
				List<String> pathSegments = uri.getPathSegments();
				StringBuilder path = new StringBuilder(pathSegments.get(0));
				for (int i = 1; i < pathSegments.size() - 1; i++) {
					path.append('/').append(pathSegments.get(i));
				}
				path.append('/').append(jarUri.getPath());
				jarUri = uri.buildUpon().path(path.toString()).build();
			} else {
				jarUri = jarUri.buildUpon().scheme("http").build();
			}
		}
		String url = jarUri.toString();
		Log.d(TAG, "Downloading " + url);
		Exception exception;
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setInstanceFollowRedirects(true);
			connection.setReadTimeout(3 * 60 * 1000);
			connection.setConnectTimeout(15000);
			int code = connection.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM
					|| code == HttpURLConnection.HTTP_MOVED_TEMP) {
				String urlStr = connection.getHeaderField("Location");
				connection.disconnect();
				connection = (HttpURLConnection) new URL(urlStr).openConnection();
				connection.setInstanceFollowRedirects(true);
				connection.setReadTimeout(3 * 60 * 1000);
				connection.setConnectTimeout(15000);
			}
			try (InputStream inputStream = connection.getInputStream();
				 OutputStream outputStream = new FileOutputStream(srcJar)) {
				byte[] buffer = new byte[2048];
				int length;
				while ((length = inputStream.read(buffer)) > 0) {
					outputStream.write(buffer, 0, length);
				}
			}
			connection.disconnect();
			Log.d(TAG, "Download complete");
			return;
		} catch (MalformedURLException e) {
			exception = e;
		} catch (FileNotFoundException e) {
			exception = e;
		} catch (IOException e) {
			exception = e;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
		deleteTemp();
		throw new ConverterException("Can't download jar", exception);
	}

	void deleteTemp() {
		if (tmpDir != null) {
			FileUtils.deleteDirectory(tmpDir);
		}
	}

	public String getJar() {
		return srcJar == null ? null : srcJar.getAbsolutePath();
	}

	void clearCache() {
		FileUtils.deleteDirectory(cacheDir);
	}

	String getIconPath() {
		return targetDir.getAbsolutePath() + Config.MIDLET_ICON_FILE;
	}

	public AppItem getExistsApp() {
		return currentApp;
	}
}
