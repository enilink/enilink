package net.enilink.core.fileinstall;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Dictionary;

import org.apache.felix.fileinstall.ArtifactInstaller;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.log.LogService;

import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.LinkedHashGraph;
import net.enilink.komma.core.visitor.IDataVisitor;
import net.enilink.komma.model.ModelUtil;

public final class RdfConfigurationArtifactInstaller implements ArtifactInstaller {
	private static final String FILENAME_KEY = RdfConfigurationArtifactInstaller.class.getName() + ".filename";

	private ConfigurationAdmin configAdmin;
	private LogService logService;

	public boolean canHandle(File artifact) {
		if (!artifact.getName().endsWith(".ttl")) {
			return false;
		}
		IGraph config = null;
		try {
			config = readConfigFile(artifact);
		} catch (Exception e) {
			log(LogService.LOG_DEBUG, "Unable to read config file " + artifact.getName(), e);
		}
		return config != null;
	}

	public void install(File artifact) throws Exception {
		String name = artifact.getName();
		log(LogService.LOG_DEBUG, "Installing configuration file " + name);

		// remove file extension
		String pid = name.substring(0, name.lastIndexOf('.'));

		Configuration configuration = configAdmin.getConfiguration(pid, null);
		if (configuration != null) {
			Dictionary<String, Object> properties = configuration.getProperties();
			properties.put(FILENAME_KEY, artifact.getName());
			IGraph config = null;
			try {
				config = readConfigFile(artifact);
				properties.put("enilink.config", config);
			} catch (Exception e) {
				log(LogService.LOG_DEBUG, "Unable to read config file " + artifact.getName(), e);
			}
			configuration.update(properties);
		}
	}

	public void log(int level, String message) {
		log(level, message, null);
	}

	public void log(int level, String message, Throwable exception) {
		if (logService != null) {
			logService.log(level, message, exception);
		}
	}

	private IGraph readConfigFile(File artifact) throws Exception {
		final InputStream stream = new BufferedInputStream(new FileInputStream(artifact));
		final IGraph config = new LinkedHashGraph();
		ModelUtil.readData(stream, "base:", "text/turtle", new IDataVisitor<Void>() {
			public Void visitBegin() {
				return null;
			}

			public Void visitEnd() {
				return null;
			}

			public Void visitStatement(IStatement stmt) {
				config.add(stmt);
				return null;
			}
		});
		return config;
	}

	public void setConfigAdmin(ConfigurationAdmin configAdmin) {
		this.configAdmin = configAdmin;
	}

	public void setLogService(LogService log) {
		this.logService = log;
	}

	public void uninstall(File artifact) throws Exception {
		log(LogService.LOG_DEBUG, "Uninstalling configuration file " + artifact.getName());

		Configuration[] configs = configAdmin.listConfigurations("(" + FILENAME_KEY + "=" + artifact.getName() + ")");
		if (configs != null) {
			for (Configuration config : configs) {
				config.delete();
			}
		}
	}

	public void update(File artifact) throws Exception {
		log(LogService.LOG_DEBUG, "Updating metatype configuration file " + artifact.getName());

		// maybe uninstall is not desired here
		// uninstall(artifact);

		// just update the current configuration
		install(artifact);
	}
}