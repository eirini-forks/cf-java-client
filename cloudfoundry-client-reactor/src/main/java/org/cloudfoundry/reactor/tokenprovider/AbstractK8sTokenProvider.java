/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudfoundry.reactor.tokenprovider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import io.kubernetes.client.util.KubeConfig;
import static io.kubernetes.client.util.Config.ENV_KUBECONFIG;
import static io.kubernetes.client.util.Config.SERVICEACCOUNT_TOKEN_PATH;
import static io.kubernetes.client.util.KubeConfig.ENV_HOME;
import static io.kubernetes.client.util.KubeConfig.KUBECONFIG;
import static io.kubernetes.client.util.KubeConfig.KUBEDIR;

public abstract class AbstractK8sTokenProvider implements TokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("cloudfoundry-client.token");

    @Override
    public void invalidate(ConnectionContext connectionContext) {
        // doesn't make sense in kubeconfig world
    }

    @Override
    public Mono<String> getToken(ConnectionContext connectionContext) {
        File kubeConfig = findConfigFromEnv();

        if (kubeConfig == null) {
            kubeConfig = findConfigInHomeDir();
        }

        if (kubeConfig == null) {
            final File tokenFile = new File(SERVICEACCOUNT_TOKEN_PATH);
            if (tokenFile.exists()) {
                byte[] encoded;
                try {
                  encoded = Files.readAllBytes(Paths.get(SERVICEACCOUNT_TOKEN_PATH));
                } catch (IOException e) {
                   LOGGER.error("IOException: "+ e.toString());
                   return Mono.empty();
                }

                return Mono.just(new String(encoded, StandardCharsets.UTF_8));
            }

            return Mono.empty();
        }

        KubeConfig kc;
        try {
            kc =  KubeConfig.loadKubeConfig(new FileReader(kubeConfig));
        } catch (FileNotFoundException e) {
            LOGGER.error("FileNotFoundException: "+ e.toString());
            return Mono.empty();
        }

        String token = kc.getAccessToken();
        if (!token.isEmpty()) {
            LOGGER.info("token: "+token);
            return Mono.just("Bearer " + token);
        }

        // TODO: return a static cert/key here
        // TODO: investigate getting a cert/key from an exec plugin too

        LOGGER.warn("no token");
        return Mono.empty();
    }

  private static File findConfigFromEnv() {
    final KubeConfigEnvParser kubeConfigEnvParser = new KubeConfigEnvParser();

    final String kubeConfigPath =
        kubeConfigEnvParser.parseKubeConfigPath(System.getenv(ENV_KUBECONFIG));
    if (kubeConfigPath == null) {
      return null;
    }

    final File kubeConfig = new File(kubeConfigPath);
    if (kubeConfig.exists()) {
      return kubeConfig;
    } else {
      LOGGER.debug("Could not find file specified in $KUBECONFIG");
      return null;
    }
  }

  private static class KubeConfigEnvParser {
    private String parseKubeConfigPath(String kubeConfigEnv) {
      if (kubeConfigEnv == null) {
        return null;
      }

      final String[] filePaths = kubeConfigEnv.split(File.pathSeparator);
      final String kubeConfigPath = filePaths[0];
      if (filePaths.length > 1) {
        LOGGER.warn(
            "Found multiple kubeconfigs files, $KUBECONFIG: " + kubeConfigEnv + " using first: {}",
            kubeConfigPath);
      }

      return kubeConfigPath;
    }
  }

  private static File findHomeDir() {
    final String envHome = System.getenv(ENV_HOME);
    if (envHome != null && envHome.length() > 0) {
      final File config = new File(envHome);
      if (config.exists()) {
        return config;
      }
    }
    if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
      String homeDrive = System.getenv("HOMEDRIVE");
      String homePath = System.getenv("HOMEPATH");
      if (homeDrive != null
          && homeDrive.length() > 0
          && homePath != null
          && homePath.length() > 0) {
        File homeDir = new File(new File(homeDrive), homePath);
        if (homeDir.exists()) {
          return homeDir;
        }
      }
      String userProfile = System.getenv("USERPROFILE");
      if (userProfile != null && userProfile.length() > 0) {
        File profileDir = new File(userProfile);
        if (profileDir.exists()) {
          return profileDir;
        }
      }
    }
    return null;
  }

  private static File findConfigInHomeDir() {
    final File homeDir = findHomeDir();
    if (homeDir != null) {
      final File config = new File(new File(homeDir, KUBEDIR), KUBECONFIG);
      if (config.exists()) {
        return config;
      }
    }
    LOGGER.debug("Could not find ~/.kube/config");
    return null;
  }


}
