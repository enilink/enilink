package net.enilink.platform.core.security;

import net.enilink.commons.util.Pair;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.platform.core.Config;
import net.enilink.platform.core.UseService;
import net.enilink.vocab.rdfs.RDFS;
import org.osgi.framework.FrameworkUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LoginUtil {
	static URI configURI = URIs.createURI("plugin://net.enilink.platform.core/Login/");

	public static Boolean REQUIRE_LOGIN = "true".equalsIgnoreCase(System.getProperty("enilink.loginrequired"));

	public static URL getJaasConfigUrl() {
		return new UseService<Config, URL>(Config.class) {
			@Override
			protected URL withService(Config config) {
				return Optional.ofNullable(config.filter(configURI,
						configURI.appendLocalPart("jaasConfigUrl"), null).objectString()).map(url -> {
					try {
						return new URL(url);
					} catch (MalformedURLException e) {
						return null;
					}
				}).orElse(FrameworkUtil.getBundle(getClass()).getResource("/config/jaas.conf"));
			}
		}.getResult();
	}

	public static List<Pair<String, String>> getLoginMethods() {
		return new UseService<Config, List<Pair<String, String>>>(Config.class) {
			@Override
			protected List<Pair<String, String>> withService(Config config) {
				List<Pair<String, String>> methods = config.filter(configURI, configURI.appendLocalPart("loginModule"), null).objects().stream()
						.filter(v -> v instanceof IReference).flatMap(module -> {
							Optional<String> name = Optional.ofNullable(config.filter((IReference) module,
									configURI.appendLocalPart("jaasConfigName"), null).objectString());
							Optional<String> label = Optional.ofNullable(config.filter((IReference) module,
									RDFS.PROPERTY_LABEL, null).objectString());

							if (name.isPresent()) {
								return Stream.of(name.map(n -> new Pair<>(label.orElse(n), n)).get());
							} else {
								return Stream.empty();
							}
						}).collect(Collectors.toList());
				if (methods.isEmpty()) {
					return Arrays.asList(new Pair<>("eniLINK", "eniLINK"));
				} else {
					return methods;
				}
			}
		}.getResult();
	}
}
