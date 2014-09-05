package org.motechproject.server.impl;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.framework.Felix;
import org.eclipse.gemini.blueprint.OsgiException;
import org.eclipse.gemini.blueprint.util.OsgiBundleUtils;
import org.motechproject.config.core.domain.BootstrapConfig;
import org.motechproject.config.core.service.impl.mapper.BootstrapConfigPropertyMapper;
import org.motechproject.server.event.BundleErrorEventListener;
import org.motechproject.server.api.BundleLoader;
import org.motechproject.server.api.BundleLoadingException;
import org.motechproject.server.api.JarInformation;
import org.motechproject.server.osgi.PlatformConstants;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.Constants;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;

import javax.servlet.ServletContext;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Hashtable;
import java.util.Dictionary;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Class for initializing and starting the OSGi framework.
 * Also registers a startup listener and HttpService listener
 * and store bundle classloaders.
 */
public class OsgiFrameworkService implements ApplicationContextAware {

    private static Logger logger = LoggerFactory.getLogger(OsgiFrameworkService.class);

    private ApplicationContext applicationContext;

    private String internalBundleFolder;

    private String externalBundleFolder;

    private String fragmentSubFolder;

    private Framework osgiFramework;

    private List<BundleLoader> bundleLoaders;

    private Map<String, String> bundleLocationMapping = new HashMap<>();

    public void init(BootstrapConfig bootstrapConfig) {
        try (InputStream is = Felix.class.getResourceAsStream("/osgi.properties");) {
            Properties properties = new Properties();
            properties.load(is);
            if ( bootstrapConfig != null ) {
                Properties bootstrapProperties = BootstrapConfigPropertyMapper.toProperties(bootstrapConfig);
                if (bootstrapProperties.containsKey("org.osgi.framework.storage")) {
                    properties.setProperty("org.osgi.framework.storage", bootstrapProperties.getProperty("org.osgi.framework.storage"));
                }
            }

            this.setOsgiFramework(new Felix(properties));

            logger.info("Initializing OSGi framework");

            ServletContext servletContext = ((WebApplicationContext) applicationContext).getServletContext();

            osgiFramework.init();

            BundleContext bundleContext = osgiFramework.getBundleContext();

            // This is mandatory for Felix http servlet bridge
            servletContext.setAttribute(BundleContext.class.getName(), bundleContext);

            if ( bootstrapConfig != null ) {
                logger.info("Installing all available bundles");

                installAllBundles(servletContext, bundleContext);

                registerBundleLoaderExecutor();
            }

            try {
                registerBundleErrorEventListener();
            } catch (Exception e) {
                logger.warn("Unable to register listener.");
            }

            logger.info("OSGi framework initialization finished");
        } catch (Exception e) {
            logger.error("Failed to start OSGi framework", e);
            throw new OsgiException(e);
        }
    }

    /**
     * Initialize, install and start bundles and the OSGi framework
     */
    public void start() {
        try {
            logger.info("Starting OSGi framework");

            osgiFramework.start();

            Bundle platformBundle = OsgiBundleUtils.findBundleBySymbolicName(osgiFramework.getBundleContext(),
                    PlatformConstants.PLATFORM_BUNDLE_SYMBOLIC_NAME);

            platformBundle.start();

            logger.info("Starting the Felix framework");

            logger.info("OSGi framework started");
        } catch (Exception e) {
            logger.error("Failed to start OSGi framework", e);
            throw new OsgiException(e);
        }
    }

    private void installAllBundles(ServletContext servletContext, BundleContext bundleContext) throws IOException, BundleLoadingException {
        for (URL url : findBundles(servletContext)) {
            if (!isBundleAndEligibleForInstall(url)) {
                logger.debug("Skipping :" + url);
                continue;
            }
            logger.debug("Installing bundle [" + url + "]");
            try {
                Bundle bundle = bundleContext.installBundle(url.toExternalForm());
                bundleLocationMapping.put(bundle.getBundleId() + ".0", bundle.getLocation());
            } catch (BundleException e) {
                throw new BundleLoadingException("Failed to install bundle from " + url, e);
            }
        }
    }


    private void registerBundleLoaderExecutor() {
        /* bundle loader extensions will be registered so that custom loaders like JSPBundle
           loader can watch for other bundles and run extension service*/
        new BundleTracker(osgiFramework.getBundleContext(), Bundle.STARTING, null) {
            @Override
            public Object addingBundle(Bundle bundle, BundleEvent event) {
                // custom bundle loaders
                if (bundleLoaders != null) {
                    for (BundleLoader loader : bundleLoaders) {
                        try {
                            loader.loadBundle(bundle);
                        } catch (Exception e) {
                            logger.error("Error while running custom bundle loader " + loader.getClass().getName() + " Error: " + e.getMessage());
                        }
                    }
                }
                return super.addingBundle(bundle, event);
            }
        }.open();
    }

    private boolean isBundleAndEligibleForInstall(URL url) throws IOException {
        try (JarInputStream jarStream = new JarInputStream(url.openStream())) {
            Manifest mf = jarStream.getManifest();
            String symbolicName = mf.getMainAttributes().getValue(JarInformation.BUNDLE_SYMBOLIC_NAME);
            // we want to ignore the generated entities bundle, MDS will handle starting this bundle itself
            return symbolicName != null && !PlatformConstants.MDS_ENTITIES_BUNDLE.equals(symbolicName);
        }
    }

    /**
     * Stop the OSGi framework.
     */
    public void stop() {
        try {
            if (osgiFramework != null) {
                osgiFramework.stop();
                logger.info("OSGi framework stopped");
            }
        } catch (Exception e) {
            logger.error("Error stopping OSGi framework", e);
            throw new OsgiException(e);
        }
    }

    public String getBundleLocationByBundleId(String bundleId) {
        return bundleLocationMapping.get(bundleId);
    }

    private List<URL> findBundles(ServletContext servletContext) throws IOException {
        List<URL> list = findFragmentBundles(); //start with fragment bundles
        list.addAll(findInternalBundles(servletContext));
        list.addAll(findExternalBundles());
        return list;
    }

    /**
     * Find built-in/mandatory bundles
     *
     * @param servletContext
     * @return
     * @throws MalformedURLException
     */
    private List<URL> findInternalBundles(ServletContext servletContext) throws MalformedURLException {
        List<URL> list = new ArrayList<>();
        if (StringUtils.isNotBlank(internalBundleFolder)) {
            @SuppressWarnings("unchecked")
            Set<String> paths = servletContext.getResourcePaths(internalBundleFolder);
            if (paths != null) {
                for (String path : paths) {
                    if (path.endsWith(".jar")) {
                        URL url = servletContext.getResource(path);
                        if (url != null) {
                            list.add(url);
                        }
                    }
                }
            }
        }
        return list;
    }

    /**
     * Find external/optional bundles
     *
     * @return
     * @throws java.io.IOException
     */
    private List<URL> findExternalBundles() throws IOException {
        List<URL> list = new ArrayList<>();
        if (StringUtils.isNotBlank(externalBundleFolder)) {
            File folder = new File(externalBundleFolder);
            boolean exists = folder.exists();

            if (!exists) {
                exists = folder.mkdirs();
            }

            if (exists) {
                File[] files = folder.listFiles((FileFilter) new SuffixFileFilter(".jar"));
                list.addAll(Arrays.asList(FileUtils.toURLs(files)));
            }
        }

        return list;
    }

    private List<URL> findFragmentBundles() throws IOException {
        List<URL> list = new ArrayList<>();
        String fragmentDirName = buildFragmentDirName();
        if (StringUtils.isNotBlank(fragmentDirName)) {
            File fragmentDir = new File(fragmentDirName);
            boolean exists = fragmentDir.exists();

            if (!exists) {
                exists = fragmentDir.mkdirs();
            }

            if (exists) {
                File[] files = fragmentDir.listFiles((FileFilter) new SuffixFileFilter(".jar"));
                list.addAll(Arrays.asList(FileUtils.toURLs(files)));
            }
        }
        return list;
    }

    private String buildFragmentDirName() {
        String result = null;
        if (StringUtils.isNotBlank(externalBundleFolder)) {
            StringBuilder sb = new StringBuilder(externalBundleFolder);
            if (!externalBundleFolder.endsWith(File.separator)) {
                sb.append(File.separatorChar);
            }
            sb.append(fragmentSubFolder);
            result = sb.toString();
        }

        return result;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    public void setInternalBundleFolder(String bundleFolder) {
        this.internalBundleFolder = bundleFolder;
    }

    public void setExternalBundleFolder(String externalBundleFolder) {
        this.externalBundleFolder = externalBundleFolder;
    }

    public int getServerBundleStatus() {
        Bundle serverBundle = OsgiBundleUtils.findBundleBySymbolicName(osgiFramework.getBundleContext(),
                "org.motechproject.motech-platform-server-bundle");
        return serverBundle.getState();
    }

    private void registerBundleErrorEventListener() throws ClassNotFoundException, NullPointerException {
        BundleContext bundleContext = osgiFramework.getBundleContext();

        Bundle eventAdminBundle = OsgiBundleUtils.findBundleBySymbolicName(bundleContext, "org.apache.felix.eventadmin");
        String activator = eventAdminBundle.getHeaders().get(Constants.BUNDLE_ACTIVATOR);
        Class activatorClass = eventAdminBundle.loadClass(activator);
        ClassLoader eventAdminCl = activatorClass.getClassLoader();
        Class<?> eventHandlerClass = eventAdminCl.loadClass(EventHandler.class.getName());

        Object proxy = Proxy.newProxyInstance(eventAdminCl, new Class[]{eventHandlerClass}, new BundleErrorEventListener());

        Dictionary<String, String[]> properties = new Hashtable<>();
        properties.put(EventConstants.EVENT_TOPIC, new String[]{PlatformConstants.BUNDLE_ERROR_TOPIC});

        bundleContext.registerService(eventHandlerClass.getName(), proxy, properties);
    }

    public boolean isErrorOccurred() {
        return BundleErrorEventListener.isBundleError();
    }

    public void setOsgiFramework(Framework osgiFramework) {
        this.osgiFramework = osgiFramework;
    }

    public void setBundleLoaders(List<BundleLoader> bundleLoaders) {
        this.bundleLoaders = bundleLoaders;
    }

    public String getInternalBundleFolder() {
        return internalBundleFolder;
    }

    public String getExternalBundleFolder() {
        return externalBundleFolder;
    }

    public String getFragmentSubFolder() {
        return fragmentSubFolder;
    }

    public void setFragmentSubFolder(String fragmentSubFolder) {
        this.fragmentSubFolder = fragmentSubFolder;
    }
}
