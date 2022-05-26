package io.github.fvarrui.javapackager.packagers;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import groovyjarjarpicocli.CommandLine;
import io.github.fvarrui.javapackager.model.NotarizationConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

import io.github.fvarrui.javapackager.model.Platform;
import io.github.fvarrui.javapackager.utils.CommandUtils;
import io.github.fvarrui.javapackager.utils.FileUtils;
import io.github.fvarrui.javapackager.utils.Logger;
import io.github.fvarrui.javapackager.utils.VelocityUtils;
import io.github.fvarrui.javapackager.utils.VersionUtils;
import io.github.fvarrui.javapackager.utils.XMLUtils;

/**
 * Packager for Mac OS X
 */
public class MacPackager extends Packager {
	
	private File appFile;
	private File contentsFolder;
	private File resourcesFolder;
	private File javaFolder;
	private File macOSFolder;
	
	public File getAppFile() {
		return appFile;
	}

	@Override
	public void doInit() throws Exception {

		this.macConfig.setDefaults(this);
	
		// FIX useResourcesAsWorkingDir=false doesn't work fine on Mac OS (option disabled) 
		if (!this.isUseResourcesAsWorkingDir()) {
			this.useResourcesAsWorkingDir = true;
			Logger.warn("'useResourcesAsWorkingDir' property disabled on Mac OS (useResourcesAsWorkingDir is always true)");
		}
		
	}

	@Override
	protected void doCreateAppStructure() throws Exception {
		
		// initializes the references to the app structure folders
		this.appFile = new File(appFolder, name + ".app");
		this.contentsFolder = new File(appFile, "Contents");
		this.resourcesFolder = new File(contentsFolder, "Resources");
		this.javaFolder = new File(resourcesFolder, this.macConfig.isRelocateJar() ? "Java" : "");
		this.macOSFolder = new File(contentsFolder, "MacOS");

		// makes dirs
		
		FileUtils.mkdir(this.appFile);
		Logger.info("App file folder created: " + appFile.getAbsolutePath());
		
		FileUtils.mkdir(this.contentsFolder);
		Logger.info("Contents folder created: " + contentsFolder.getAbsolutePath());
		
		FileUtils.mkdir(this.resourcesFolder);
		Logger.info("Resources folder created: " + resourcesFolder.getAbsolutePath());
		
		FileUtils.mkdir(this.javaFolder);
		Logger.info("Java folder created: " + javaFolder.getAbsolutePath());
		
		FileUtils.mkdir(this.macOSFolder);
		Logger.info("MacOS folder created: " + macOSFolder.getAbsolutePath());

		// sets common folders
		this.executableDestinationFolder = macOSFolder;
		this.jarFileDestinationFolder = javaFolder;
		this.jreDestinationFolder = new File(contentsFolder, "PlugIns/" + jreDirectoryName + "/Contents/Home");
		this.resourcesDestinationFolder = resourcesFolder;

	}
	
	/**
	 * Creates a native MacOS app bundle
	 */
	@Override
	public File doCreateApp() throws Exception {
		

		// copies jarfile to Java folder
		FileUtils.copyFileToFolder(jarFile, javaFolder);
		
		if (this.administratorRequired) {

			// sets startup file
			this.executable = new File(macOSFolder, "startup");			
			
			// creates startup file to boot java app
			VelocityUtils.render("mac/startup.vtl", executable, this);
			executable.setExecutable(true, false);
			Logger.info("Startup script file created in " + executable.getAbsolutePath());

		} else {
			
			// sets startup file
			this.executable = new File(macOSFolder, "universalJavaApplicationStub_");
			Logger.info("Using " + executable.getAbsolutePath() + " as startup script");
			
		}
		
		// copies universalJavaApplicationStub startup file to boot java app
		File appStubFile = new File(macOSFolder, "universalJavaApplicationStub");
		File customStubFile = new File(macOSFolder, "universalJavaApplicationStub_");
		FileUtils.copyResourceToFile("/mac/universalJavaApplicationStub", appStubFile, true);
		FileUtils.copyResourceToFile("/mac/universalJavaApplicationStub_", customStubFile, true);

		FileUtils.processFileContent(appStubFile, content -> {
			if (!macConfig.isRelocateJar()) {
				content = content.replaceAll("/Contents/Resources/Java", "/Contents/Resources");
			}
			content = content.replaceAll("\\$\\{info.name\\}", this.name);
			return content;
		});
		appStubFile.setExecutable(true, false);
		customStubFile.setExecutable(true, false);
		
		// process classpath
		classpath = (this.macConfig.isRelocateJar() ? "Java/" : "") + this.jarFile.getName() + (classpath != null ? ":" + classpath : "");
		classpaths = Arrays.asList(classpath.split("[:;]"));
		if (!isUseResourcesAsWorkingDir()) {
			classpaths = classpaths.stream().map(cp -> new File(cp).isAbsolute() ? cp : "$ResourcesFolder/" + cp).collect(Collectors.toList());
		}
		classpath = StringUtils.join(classpaths, ":");

		// creates and write the Info.plist file
		File infoPlistFile = new File(contentsFolder, "Info.plist");
		VelocityUtils.render("mac/Info.plist.vtl", infoPlistFile, this);
		XMLUtils.prettify(infoPlistFile);
		Logger.info("Info.plist file created in " + infoPlistFile.getAbsolutePath());

		// codesigns app folder
		if (!Platform.mac.isCurrentPlatform()) {
			Logger.warn("Generated app could not be signed due to current platform is " + Platform.getCurrentPlatform());
		} else if (!getMacConfig().isCodesignApp()) {
			Logger.warn("App codesigning disabled");
		} else {
			codesign(this.macConfig.getDeveloperId(), this.macConfig.getEntitlements(), this.appFile);
			if (!this.macConfig.isNotarizeApp()) {
				Logger.warn("Notarizing is disabled");
			} else {
				NotarizationConfig notConf = this.macConfig.getNotarizationConfig();
				if (!notConf.areConfigurationsValid()) {
					Logger.warn("Notarizing configurations are not properly set");
				} else {
					notarize(notConf.getAppleID(), notConf.getTeamID(), notConf.getAppSpecificPassword());
				}
			}
		}
		return appFile;
	}

	private void makeDirWritable(String dirPath) throws IOException, CommandLineException {
		ArrayList<String> arguments = new ArrayList();
		arguments.add("-R");
		arguments.add("+rw");
		arguments.add(dirPath);
		CommandUtils.execute("chmod", arguments.toArray());
	}

	private void codesign(String developerId, File entitlements, File appFile) throws IOException, CommandLineException {
		List<String> flags = new ArrayList<>();
		if (VersionUtils.compareVersions("10.13.6", SystemUtils.OS_VERSION) >= 0) {
			flags.add("runtime"); // enable hardened runtime if Mac OS version >= 10.13.6 
		} else {
			Logger.warn("Mac OS version detected: " + SystemUtils.OS_VERSION + " ... hardened runtime disabled!"); 		
		}
		
		List<Object> codesignArgs = new ArrayList<>();
		codesignArgs.add("-f");
		codesignArgs.add("--verbose=4");
		codesignArgs.add("--deep");
		codesignArgs.add("--strict");
		codesignArgs.add("-s");
		codesignArgs.add(developerId);
		if (!flags.isEmpty()) {
			codesignArgs.add("--options");
			codesignArgs.add(StringUtils.join(flags, ","));
		}
		if (entitlements == null) {
			Logger.warn("Entitlements file not specified");
		} else if (!entitlements.exists()) {
			Logger.warn("Entitlements file doesn't exist: " + entitlements);
		} else {
			codesignArgs.add("--entitlements");
			codesignArgs.add(entitlements.getPath());
		}
		codesignArgs.add(appFile.getPath());
		// JRE not writable fix
		makeDirWritable(appFile.getPath());
		CommandUtils.execute("codesign", codesignArgs.toArray(new Object[codesignArgs.size()]));
	}

	private void notarize(String appleId, String teamID, String password) throws IOException, CommandLineException {
		// Zip the App folder
		File zippedFolder = zipAppFolder();

		List<Object> notarizeArgs = new ArrayList<>();
		// xcrun notarytool submit --apple-id "kiroto50@gmail.com" --team-id 3J7EGFTQ3H BeatBuddyLoader.zip --wait
		notarizeArgs.add("notarytool");
		notarizeArgs.add("submit");
		notarizeArgs.add("--apple-id");
		notarizeArgs.add(appleId);
		notarizeArgs.add("--team-id");
		notarizeArgs.add(teamID);
		notarizeArgs.add("--password");
		notarizeArgs.add(password);
		notarizeArgs.add("--wait");
		notarizeArgs.add(zippedFolder);
		// Send the zip to notarize
		CommandUtils.execute("xcrun", notarizeArgs.toArray(new Object[notarizeArgs.size()]));
		// Unzip the folder (should end up in the same location with the same paths)
		unzip(zippedFolder);
	}

	private File zipAppFolder() throws IOException, CommandLineException {
		File outputFile = new File(appFolder.getParentFile(), name + ".zip");
		List<Object> zipArgs = new ArrayList<>();
		zipArgs.add("-r");
		zipArgs.add(outputFile.getPath());
		zipArgs.add(appFolder);
		CommandUtils.execute("zip", zipArgs.toArray(new Object[zipArgs.size()]));
		return outputFile;
	}

	private void unzip(File file) throws IOException, CommandLineException {
		List<Object> unzipArgs = new ArrayList<>();
		unzipArgs.add("-o");
		unzipArgs.add(file.getPath());
		CommandUtils.execute("unzip", unzipArgs.toArray(new Object[unzipArgs.size()]));
	}
}
