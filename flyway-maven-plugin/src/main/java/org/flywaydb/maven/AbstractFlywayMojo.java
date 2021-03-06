/**
 * Copyright 2010-2014 Axel Fontaine
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.internal.util.ExceptionUtils;
import org.flywaydb.core.internal.util.Location;
import org.flywaydb.core.internal.util.logging.Log;
import org.flywaydb.core.internal.util.logging.LogFactory;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.cipher.PlexusCipherException;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

/**
 * Common base class for all mojos with all common attributes.<br>
 *
 * @requiresDependencyResolution test
 * @configurator include-project-dependencies
 * @phase pre-integration-test
 */
@SuppressWarnings({"JavaDoc", "FieldCanBeLocal", "UnusedDeclaration"})
abstract class AbstractFlywayMojo extends AbstractMojo {
    /**
     * Property name prefix for placeholders that are configured through properties.
     */
    private static final String PLACEHOLDERS_PROPERTY_PREFIX = "flyway.placeholders.";

    protected Log log;

    protected Flyway flyway = new Flyway();

    /**
     * Whether to skip the execution of the Maven Plugin for this module.<br/>
     * <p>Also configurable with Maven or System Property: ${flyway.skip}</p>
     *
     * @parameter property="flyway.skip"
     */
    /* private -> for testing */ boolean skip;

    /**
     * The fully qualified classname of the jdbc driver to use to connect to the database.<br>
     * By default, the driver is autodetected based on the url.<br/>
     * <p>Also configurable with Maven or System Property: ${flyway.driver}</p>
     *
     * @parameter property="flyway.driver"
     */
    /* private -> for testing */ String driver;

    /**
     * The jdbc url to use to connect to the database.<br>
     * <p>Also configurable with Maven or System Property: ${flyway.url}</p>
     *
     * @parameter property="flyway.url"
     */
    /* private -> for testing */ String url;

    /**
     * The user to use to connect to the database. (default: <i>blank</i>)<br>
     * The credentials can be specified by user/password or {@code serverId} from settings.xml
     * <p>Also configurable with Maven or System Property: ${flyway.user}</p>
     *
     * @parameter property="flyway.user"
     */
    /* private -> for testing */ String user;

    /**
     * The password to use to connect to the database. (default: <i>blank</i>)<br>
     * <p>Also configurable with Maven or System Property: ${flyway.password}</p>
     *
     * @parameter property="flyway.password"
     */
    private String password;

    /**
     * List of the schemas managed by Flyway. These schema names are case-sensitive.<br/>
     * (default: The default schema for the datasource connection)
     * <p>Consequences:</p>
     * <ul>
     * <li>The first schema in the list will be automatically set as the default one during the migration.</li>
     * <li>The first schema in the list will also be the one containing the metadata table.</li>
     * <li>The schemas will be cleaned in the order of this list.</li>
     * </ul>
     * <p>Also configurable with Maven or System Property: ${flyway.schemas} (comma-separated list)</p>
     *
     * @parameter property="flyway.schemas"
     */
    private String[] schemas;

    /**
     * Whether the multiple db mode option is enabled or not 
     * 
     * @parameter property="flyway.multipleDbMode"
     */
    private boolean multipleDbMode = flyway.isMultipleDbMode();
    
    /**
     * <p>The name of the metadata table that will be used by Flyway. (default: schema_version)</p>
     * <p> By default (single-schema mode) the
     * metadata table is placed in the default schema for the connection provided by the datasource. <br/> When the
     * {@code flyway.schemas} property is set (multi-schema mode), the metadata table is placed in the first schema of
     * the list. </p>
     * <p>Also configurable with Maven or System Property: ${flyway.table}</p>
     *
     * @parameter property="flyway.table"
     */
    private String table = flyway.getTable();

    /**
     * The version to tag an existing schema with when executing init. (default: 1)<br/>
     * <p>Also configurable with Maven or System Property: ${flyway.initVersion}</p>
     *
     * @parameter property="flyway.initVersion"
     */
    private String initVersion = flyway.getInitVersion().getVersion();

    /**
     * The description to tag an existing schema with when executing init. (default: << Flyway Init >>)<br>
     * <p>Also configurable with Maven or System Property: ${flyway.initDescription}</p>
     *
     * @parameter property="flyway.initDescription"
     */
    private String initDescription = flyway.getInitDescription();

    /**
     * Locations on the classpath to scan recursively for migrations. Locations may contain both sql
     * and java-based migrations. (default: db/migration)
     * <p>Also configurable with Maven or System Property: ${flyway.locations} (Comma-separated list)</p>
     *
     * @parameter
     */
    private String[] locations = flyway.getLocations();

    /**
     * The fully qualified class names of the custom MigrationResolvers to be used in addition to the built-in ones for
     * resolving Migrations to apply.
     * <p>(default: none)</p>
     * <p>Also configurable with Maven or System Property: ${flyway.resolvers} (Comma-separated list)</p>
     *
     * @parameter
     */
    private String[] resolvers = new String[0];

    /**
     * The encoding of Sql migrations. (default: UTF-8)<br> <p>Also configurable with Maven or System Property:
     * ${flyway.encoding}</p>
     *
     * @parameter property="flyway.encoding"
     */
    private String encoding = flyway.getEncoding();

    /**
     * The file name prefix for Sql migrations (default: V) <p>Also configurable with Maven or System Property:
     * ${flyway.sqlMigrationPrefix}</p>
     *
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @parameter property="flyway.sqlMigrationPrefix"
     */
    private String sqlMigrationPrefix = flyway.getSqlMigrationPrefix();

    /**
     * The file name separator for Sql migrations (default: __) <p>Also configurable with Maven or System Property:
     * ${flyway.sqlMigrationSeparator}</p>
     *
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @parameter property="flyway.sqlMigrationSeparator"
     */
    private String sqlMigrationSeparator = flyway.getSqlMigrationSeparator();

    /**
     * The file name suffix for Sql migrations (default: .sql) <p>Also configurable with Maven or System Property:
     * ${flyway.sqlMigrationSuffix}</p>
     *
     * <p>Sql migrations have the following file name structure: prefixVERSIONseparatorDESCRIPTIONsuffix ,
     * which using the defaults translates to V1_1__My_description.sql</p>
     *
     * @parameter property="flyway.sqlMigrationSuffix"
     */
    private String sqlMigrationSuffix = flyway.getSqlMigrationSuffix();

    /**
     * Whether to automatically call clean or not when a validation error occurs. (default: {@code false})<br/>
     * <p> This is exclusively intended as a convenience for development. Even tough we
     * strongly recommend not to change migration scripts once they have been checked into SCM and run, this provides a
     * way of dealing with this case in a smooth manner. The database will be wiped clean automatically, ensuring that
     * the next migration will bring you back to the state checked into SCM.</p>
     * <p><b>Warning ! Do not enable in production !</b></p><br/>
     * <p>Also configurable with Maven or System Property: ${flyway.cleanOnValidationError}</p>
     *
     * @parameter property="flyway.cleanOnValidationError"
     */
    private boolean cleanOnValidationError = flyway.isCleanOnValidationError();

    /**
     * The target version up to which Flyway should run migrations. Migrations with a higher version number will not be
     * applied. (default: the latest version)
     * <p>Also configurable with Maven or System Property: ${flyway.target}</p>
     *
     * @parameter property="flyway.target"
     */
    private String target = flyway.getTarget().getVersion();

    /**
     * Allows migrations to be run "out of order" (default: {@code false}).
     * <p>If you already have versions 1 and 3 applied, and now a version 2 is found,
     * it will be applied too instead of being ignored.</p>
     * <p>Also configurable with Maven or System Property: ${flyway.outOfOrder}</p>
     *
     * @parameter property="flyway.outOfOrder"
     */
    private boolean outOfOrder = flyway.isOutOfOrder();

    /**
     * Ignores failed future migrations when reading the metadata table. These are migrations that we performed by a
     * newer deployment of the application that are not yet available in this version. For example: we have migrations
     * available on the classpath up to version 3.0. The metadata table indicates that a migration to version 4.0
     * (unknown to us) has already been attempted and failed. Instead of bombing out (fail fast) with an exception, a
     * warning is logged and Flyway terminates normally. This is useful for situations where a database rollback is not
     * an option. An older version of the application can then be redeployed, even though a newer one failed due to a
     * bad migration. (default: false)
     * <p>Also configurable with Maven or System Property: ${flyway.ignoreFailedFutureMigration}</p>
     *
     * @parameter property="flyway.ignoreFailedFutureMigration"
     */
    private boolean ignoreFailedFutureMigration = flyway.isIgnoreFailedFutureMigration();

    /**
     * A map of &lt;placeholder, replacementValue&gt; to apply to sql migration scripts.
     * <p/>
     * <p>Also configurable with Maven or System Properties like ${flyway.placeholders.myplaceholder} or ${flyway.placeholders.otherone}</p>
     *
     * @parameter
     */
    private Map<String, String> placeholders = flyway.getPlaceholders();

    /**
     * The prefix of every placeholder. (default: ${ )<br>
     * <p>Also configurable with Maven or System Property: ${flyway.placeholderPrefix}</p>
     *
     * @parameter property="flyway.placeholderPrefix"
     */
    private String placeholderPrefix = flyway.getPlaceholderPrefix();

    /**
     * The suffix of every placeholder. (default: } )<br>
     * <p>Also configurable with Maven or System Property: ${flyway.placeholderSuffix}</p>
     *
     * @parameter property="flyway.placeholderSuffix"
     */
    private String placeholderSuffix = flyway.getPlaceholderSuffix();

    /**
     * An array of FlywayCallback implementations. (default: empty )<br>
     * <p>Also configurable with Maven or System Property: ${flyway.callbacks}</p>
     *
     * @parameter
     */
    private String[] callbacks = new String[0];

    /**
     * <p>
     * Whether to automatically call init when migrate is executed against a non-empty schema with no metadata table.
     * This schema will then be initialized with the {@code initialVersion} before executing the migrations.
     * Only migrations above {@code initialVersion} will then be applied.
     * </p>
     * <p>
     * This is useful for initial Flyway production deployments on projects with an existing DB.
     * </p>
     * <p>
     * Be careful when enabling this as it removes the safety net that ensures
     * Flyway does not migrate the wrong database in case of a configuration mistake! (default: {@code false})
     * </p>
     * <p>Also configurable with Maven or System Property: ${flyway.initOnMigrate}</p>
     *
     * @parameter property="flyway.initOnMigrate"
     */
    private boolean initOnMigrate = flyway.isInitOnMigrate();

    /**
     * Whether to automatically call validate or not when running migrate. (default: {@code true})<br/>
     * <p>Also configurable with Maven or System Property: ${flyway.validationErrorMode}</p>
     *
     * @parameter property="flyway.validateOnMigrate"
     */
    private boolean validateOnMigrate = flyway.isValidateOnMigrate();

    /**
     * Properties file from which to load the Flyway configuration. The names of the individual properties match the ones you would
     * use as Maven or System properties. The encoding of the file must be the same as the encoding defined with the
     * flyway.encoding property, which is UTF-8 by default. Relative paths are relative to the POM. (default: flyway.properties)
     * <p/>
     * <p>Also configurable with Maven or System Property: ${flyway.configFile}</p>
     *
     * @parameter property="flyway.configFile"
     */
    private File configFile;

    /**
     * The id of the server tag in settings.xml (default: flyway-db)<br/>
     * The credentials can be specified by user/password or {@code serverId} from settings.xml<br>
     * <p>Also configurable with Maven or System Property: ${flyway.serverId}</p>
     *
     * @parameter property="flyway.serverId"
     */
    private String serverId = "flyway-db";

    /**
     * The link to the settings.xml
     *
     * @parameter property="settings"
     * @required
     * @readonly
     */
    private Settings settings;

    /**
     * Reference to the current project that includes the Flyway Maven plugin.
     *
     * @parameter property="project" required="true"
     */
    protected MavenProject mavenProject;

    /**
     * Load username password from settings
     *
     * @throws FlywayException when the credentials could not be loaded.
     */
    private void loadCredentialsFromSettings() throws FlywayException {
        if (user == null) {
            final Server server = settings.getServer(serverId);
            if (server != null) {
                user = server.getUsername();
                try {
                    SecDispatcher secDispatcher = new DefaultSecDispatcher() {{
                        _cipher = new DefaultPlexusCipher();
                    }};
                    password = secDispatcher.decrypt(server.getPassword());
                } catch (SecDispatcherException e) {
                    throw new FlywayException("Unable to decrypt password", e);
                } catch (PlexusCipherException e) {
                    throw new FlywayException("Unable to initialized password decryption", e);
                }
            }
        }
    }

    /**
     * Retrieves the value of this boolean property, based on the matching System on the Maven property.
     *
     * @param systemPropertyName The name of the System property.
     * @param mavenPropertyValue The value of the Maven property.
     * @return The value to use.
     */
    /* private -> for testing */ boolean getBooleanProperty(String systemPropertyName, boolean mavenPropertyValue) {
        String systemPropertyValue = System.getProperty(systemPropertyName);
        if (systemPropertyValue != null) {
            return Boolean.getBoolean(systemPropertyName);
        }
        return mavenPropertyValue;
    }

    public final void execute() throws MojoExecutionException, MojoFailureException {
        LogFactory.setLogCreator(new MavenLogCreator(this));
        log = LogFactory.getLog(getClass());

        if (getBooleanProperty("flyway.skip", skip)) {
            log.info("Skipping Flyway execution");
            return;
        }

        try {
            loadCredentialsFromSettings();

            flyway.setClassLoader(Thread.currentThread().getContextClassLoader());
            flyway.setSchemas(schemas);
            flyway.setMultipleDbMode(multipleDbMode);
            flyway.setTable(table);
            flyway.setInitVersion(initVersion);
            flyway.setInitDescription(initDescription);
            if (locations != null) {
                for (int i = 0; i < locations.length; i++) {
                    if (locations[i].startsWith(Location.FILESYSTEM_PREFIX)) {
                        String newLocation = locations[i].substring(Location.FILESYSTEM_PREFIX.length());
                        File file = new File(newLocation);
                        if (!file.isAbsolute()) {
                            file = new File(mavenProject.getBasedir(), newLocation);
                        }
                        locations[i] = Location.FILESYSTEM_PREFIX + file.getAbsolutePath();
                    }
                }
                flyway.setLocations(locations);
            }
            flyway.setResolvers(resolvers);
            flyway.setCallbacks(callbacks);
            flyway.setEncoding(encoding);
            flyway.setSqlMigrationPrefix(sqlMigrationPrefix);
            flyway.setSqlMigrationSeparator(sqlMigrationSeparator);
            flyway.setSqlMigrationSuffix(sqlMigrationSuffix);
            flyway.setCleanOnValidationError(cleanOnValidationError);
            flyway.setOutOfOrder(outOfOrder);
            flyway.setTarget(target);
            flyway.setIgnoreFailedFutureMigration(ignoreFailedFutureMigration);
            flyway.setPlaceholderPrefix(placeholderPrefix);

            flyway.setInitOnMigrate(initOnMigrate);
            flyway.setValidateOnMigrate(validateOnMigrate);

            Properties properties = new Properties();
            properties.putAll(mavenProject.getProperties());
            if (driver != null) {
                properties.setProperty("flyway.driver", driver);
            }
            if (url != null) {
                properties.setProperty("flyway.url", url);
            }
            if (user != null) {
                properties.setProperty("flyway.user", user);
            }
            if (password != null) {
                properties.setProperty("flyway.password", password);
            }
            for (String placeholer : placeholders.keySet()) {
                String value = placeholders.get(placeholer);
                properties.setProperty("flyway.placeholders." + placeholer, value == null ? "" : value);
            }
            properties.putAll(getConfigFileProperties());
            properties.putAll(System.getProperties());
            flyway.configure(properties);

            doExecute(flyway);
        } catch (Exception e) {
            throw new MojoExecutionException(e.toString(), ExceptionUtils.getRootCause(e));
        }
    }

    /**
     * Retrieve the properties from the config file (if specified).
     */
    private Properties getConfigFileProperties() throws IOException {
        Properties properties = new Properties();
        String configFileProp = System.getProperty("flyway.configFile");
        if (configFileProp != null) {
            configFile = new File(configFileProp);
            if (!configFile.isAbsolute()) {
                configFile = new File(mavenProject.getBasedir(), configFileProp);
            }
        }
        if (configFile == null) {
            File file = new File(mavenProject.getBasedir(), "flyway.properties");
            if (file.isFile() && file.canRead()) {
                configFile = file;
            } else {
                log.debug("flyway.properties not found. Skipping.");
                return properties;
            }
        } else if (!configFile.canRead() || !configFile.isFile()) {
            throw new FlywayException("Unable to read config file: " + configFile.getAbsolutePath());
        }

        properties.load(new InputStreamReader(new FileInputStream(configFile), encoding));
        return properties;
    }

    /**
     * Retrieves this property from either the system or the maven properties.
     *
     * @param name The name of the property to retrieve.
     * @return The property value. {@code null} if not found.
     */
    protected String getProperty(String name) {
        String systemProperty = System.getProperty(name);

        if (systemProperty != null) {
            return systemProperty;
        }

        return mavenProject.getProperties().getProperty(name);
    }

    /**
     * Executes this mojo.
     *
     * @param flyway The flyway instance to operate on.
     * @throws Exception any exception
     */
    protected abstract void doExecute(Flyway flyway) throws Exception;
}