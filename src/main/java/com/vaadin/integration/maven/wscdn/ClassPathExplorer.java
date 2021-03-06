/*
 * Copyright 2000-2014 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.integration.maven.wscdn;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.vaadin.integration.maven.wscdn.CvalChecker.UnreachableCvalServerException;

/**
 * Utility class to collect widgetset related information from classpath.
 * Utility will seek all directories from classpaths, and jar files having
 * "Vaadin-Widgetsets" key in their manifest file.
 * <p>
 * Used by WidgetMapGenerator and ide tools to implement some monkey coding for
 * you.
 * <p>
 * Developer notice: If you end up reading this comment, I guess you have faced
 * a sluggish performance of widget compilation or unreliable detection of
 * components in your classpaths. The thing you might be able to do is to use
 * annotation processing tool like apt to generate the needed information. Then
 * either use that information in WidgetMapGenerator or create the appropriate
 * monkey code for gwt directly in annotation processor and get rid of
 * WidgetMapGenerator. Using annotation processor might be a good idea when
 * dropping Java 1.5 support (integrated to javac in 6).
 *
 */
public class ClassPathExplorer {

    private static final String VAADIN_ADDON_VERSION_ATTRIBUTE = "Vaadin-Package-Version";

    // Manifest attributes
    public static final String VAADIN_ADDON_LICENSE = "AdVaaLicen";
    public static final String VAADIN_ADDON_NAME = "AdVaaName";
    public static final String VAADIN_ADDON_WIDGETSET = "Vaadin-Widgetsets";
    public static final String VAADIN_ADDON_VERSION = "Implementation-Version";
    public static final String VAADIN_ADDON_TITLE = "Implementation-Title";
    public static final String LINE = "----------------------------------------------------------------------------------------------------------------------";


    // License types
    public static final String VAADIN_AGPL = "agpl";
    public static final String VAADIN_CVAL = "cval";

    static CvalChecker cvalChecker = new CvalChecker();

    /**
     * File filter that only accepts directories.
     */
    private final static FileFilter DIRECTORIES_ONLY = new FileFilter() {
        @Override
        public boolean accept(File f) {
            if (f.exists() && f.isDirectory()) {
                return true;
            } else {
                return false;
            }
        }
    };

    /**
     * Contains information about widgetsets and themes found on the classpath
     *
     * @since 7.1
     */
    public static class LocationInfo {

        private final Map<String, URL> widgetsets;

        private final Map<String, URL> addonStyles;

        public LocationInfo(Map<String, URL> widgetsets, Map<String, URL> themes) {
            this.widgetsets = widgetsets;
            addonStyles = themes;
        }

        public Map<String, URL> getWidgetsets() {
            return widgetsets;
        }

        public Map<String, URL> getAddonStyles() {
            return addonStyles;
        }

    }

    private static final boolean debug = true;

    /**
     * No instantiation from outside, callable methods are static.
     */
    private ClassPathExplorer() {
    }

    /**
     * Finds the names and locations of widgetsets available on the class path.
     *
     * @param classpathLocations
     * @return map from widgetset classname to widgetset location URL
     */
    public static Map<String, URL> getAvailableWidgetSets(
            Map<String, URL> classpathLocations) throws CvalChecker.InvalidCvalException {
        return getAvailableWidgetSetsAndStylesheets(classpathLocations).
                getWidgetsets();
    }

    /**
     * Finds the names and locations of widgetsets and themes available on the
     * class path.
     *
     * @param classpathLocations
     * @return
     */
    public static LocationInfo getAvailableWidgetSetsAndStylesheets(
            Map<String, URL> classpathLocations) throws CvalChecker.InvalidCvalException {
        long start = System.currentTimeMillis();
        Map<String, URL> widgetsets = new HashMap<String, URL>();
        Map<String, URL> themes = new HashMap<String, URL>();
        Set<String> keySet = classpathLocations.keySet();
        for (String location : keySet) {
            searchForWidgetSetsAndAddonStyles(location, classpathLocations,
                    widgetsets, themes);
        }
        long end = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("Widgetsets found from classpath:\n");
        for (String ws : widgetsets.keySet()) {
            sb.append("\t");
            sb.append(ws);
            sb.append(" in ");
            sb.append(widgetsets.get(ws));
            sb.append("\n");
        }

        log(sb.toString());
        log("Search took " + (end - start) + "ms");
        return new LocationInfo(widgetsets, themes);
    }

    /**
     * Finds all GWT modules / Vaadin widgetsets and Addon styles in a valid
     * location.
     *
     * If the location is a directory, all GWT modules (files with the
     * ".gwt.xml" extension) are added to widgetsets.
     *
     * If the location is a JAR file, the comma-separated values of the
     * "Vaadin-Widgetsets" attribute in its manifest are added to widgetsets.
     *
     * @param locationString an entry in {@link #classpathLocations}
     * @param widgetsets a map from widgetset name (including package, with dots
     * as separators) to a URL (see {@link #classpathLocations}) - new entries
     * are added to this map
     */
    private static void searchForWidgetSetsAndAddonStyles(
            String locationString, Map<String, URL> inClasspathLocations,
            Map<String, URL> widgetsets,
            Map<String, URL> addonStyles) throws CvalChecker.InvalidCvalException {

        URL location = inClasspathLocations.get(locationString);
        File directory = new File(location.getFile());

        if (directory.exists() && directory.isDirectory() && !directory.
                isHidden()) {
            // Get the list of the files contained in the directory
            String[] files = directory.list();
            if (files != null) {
                for (String file : files) {
                    // we are only interested in .gwt.xml files
                    if (!file.endsWith(".gwt.xml")) {
                        continue;
                    }
                    // remove the .gwt.xml extension
                    String classname = file.substring(0, file.length() - 8);
                    String packageName = locationString.substring(locationString
                            .lastIndexOf("/") + 1);
                    classname = packageName + "." + classname;
                    if (!isWidgetset(classname)) {
                        // Only return widgetsets and not GWT modules to avoid
                        // comparing modules and widgetsets
                        continue;
                    }
                    if (!widgetsets.containsKey(classname)) {
                        String packagePath = packageName.replaceAll("\\.", "/");
                        String basePath = location.getFile().replaceAll(
                                "/" + packagePath + "$", "");
                        try {
                            URL url = new URL(location.getProtocol(),
                                    location.getHost(), location.getPort(),
                                    basePath);
                            widgetsets.put(classname, url);
                        } catch (MalformedURLException e) {
                            // should never happen as based on an existing URL,
                            // only changing end of file name/path part
                            error("Error locating the widgetset " + classname, e);
                        }
                    }
                }
            }
        } else {

            try {
                // check files in jar file, entries will list all directories
                // and files in jar

                URLConnection openConnection = location.openConnection();

                JarFile jarFile;
                if (openConnection instanceof JarURLConnection) {
                    JarURLConnection conn = (JarURLConnection) openConnection;
                    jarFile = conn.getJarFile();
                } else {
                    jarFile = new JarFile(location.getFile());
                }

                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    // No manifest so this is not a Vaadin Add-on
                    return;
                }

                // Check for widgetset attribute
                String value = manifest.getMainAttributes().getValue(
                        "Vaadin-Widgetsets");
                if (value != null) {
                    String[] widgetsetNames = value.split(",");
                    for (int i = 0; i < widgetsetNames.length; i++) {
                        String widgetsetname = widgetsetNames[i].trim();
                        if (!widgetsetname.equals("")) {
                            widgetsets.put(widgetsetname, location);
                        }
                    }

                    Attributes attribs = manifest.getMainAttributes();
                    String license = attribs.getValue(VAADIN_ADDON_LICENSE);
                    String name = attribs.getValue(VAADIN_ADDON_NAME);
                    String vers = attribs.getValue(VAADIN_ADDON_VERSION) == null ? ""
                            : attribs.getValue(VAADIN_ADDON_VERSION);
                    String title = attribs.getValue(VAADIN_ADDON_TITLE) == null ? name
                            : attribs.getValue(VAADIN_ADDON_TITLE);

                    String awidgetsets = attribs
                            .getValue(VAADIN_ADDON_WIDGETSET) == null ? name
                                    : attribs.getValue(
                                            VAADIN_ADDON_WIDGETSET);

                    if (name != null && license != null) {
                        if (VAADIN_AGPL.equals(license)) {
                            // For agpl version, we don't care
                        } else if (VAADIN_CVAL.equals(license)) {
                            // We only check cval licensed products
                            CvalInfo info;

                            try {
                                info = cvalChecker.validateProduct(name,
                                        vers,
                                        title);
                                printValidLicense(info, title, vers);
                            } catch (UnreachableCvalServerException e) {
                                info = new CvalInfo();
                                final Product product = new Product();
                                product.setName(name);
                                info.setProduct(product);
                                printServerUnreachable(title, vers);
                            }
//                          for (String w : awidgetsets.split("[, ]+")) {
//                              ret.add(new CValUiInfo(title, String
//                                      .valueOf(computeMajorVersion(vers)),
//                                      w,
//                                      info.getType()));
//                          }
                        }
                    }
                }

                // Check for theme attribute
                value = manifest.getMainAttributes().getValue(
                        "Vaadin-Stylesheets");
                if (value != null) {
                    String[] stylesheets = value.split(",");
                    for (int i = 0; i < stylesheets.length; i++) {
                        String stylesheet = stylesheets[i].trim();
                        if (!stylesheet.equals("")) {
                            addonStyles.put(stylesheet, location);
                        }
                    }
                }
            } catch (IOException e) {
                log("Error parsing jar file: " + location);
            }

        }
    }

    /**
     * Splits the current class path into entries, and filters them accepting
     * directories, Vaadin add-on JARs with widgetsets and Vaadin JARs.
     *
     * Some other non-JAR entries may also be included in the result.
     *
     * @return filtered list of class path entries
     */
    private static List<String> getRawClasspathEntries() {
        // try to keep the order of the classpath
        List<String> locations = new ArrayList<String>();

        String pathSep = System.getProperty("path.separator");
        String classpath = System.getProperty("java.class.path");

        if (classpath.startsWith("\"")) {
            classpath = classpath.substring(1);
        }
        if (classpath.endsWith("\"")) {
            classpath = classpath.substring(0, classpath.length() - 1);
        }

        debug("Classpath: " + classpath);

        String[] split = classpath.split(pathSep);
        for (String classpathEntry : split) {
            if (acceptClassPathEntry(classpathEntry)) {
                locations.add(classpathEntry);
            }
        }

        return locations;
    }

    /**
     * Determine every URL location defined by the current classpath, and it's
     * associated package name.
     *
     * See {@link #classpathLocations} for information on output format.
     *
     * @param classpathEntries raw class path entries as split from the Java
     * class path string
     * @return map of classpath locations, see {@link #classpathLocations}
     */
    private static Map<String, URL> getClasspathLocations(
            List<String> classpathEntries) {
        long start = System.currentTimeMillis();
        // try to keep the order of the classpath
        Map<String, URL> locations = new LinkedHashMap<String, URL>();
        for (String classpathEntry : classpathEntries) {
            File file = new File(classpathEntry);
            include(null, file, locations);
            System.err.println("INCLUDE: " + classpathEntry);
        }
        long end = System.currentTimeMillis();
        if (debug) {
            debug("getClassPathLocations took " + (end - start) + "ms");
        }
        return locations;
    }

    /**
     * Checks a class path entry to see whether it can contain widgets and
     * widgetsets.
     *
     * All directories are automatically accepted. JARs are accepted if they
     * have the "Vaadin-Widgetsets" attribute in their manifest or the JAR file
     * name contains "vaadin-" or ".vaadin.".
     *
     * Also other non-JAR entries may be accepted, the caller should be prepared
     * to handle them.
     *
     * @param classpathEntry class path entry string as given in the Java class
     * path
     * @return true if the entry should be considered when looking for widgets
     * or widgetsets
     */
    private static boolean acceptClassPathEntry(String classpathEntry) {
        if (!classpathEntry.endsWith(".jar")) {
            // accept all non jars (practically directories)
            return true;
        } else {
            // accepts jars that comply with vaadin-component packaging
            // convention (.vaadin. or vaadin- as distribution packages),
            if (classpathEntry.contains("vaadin-")
                    || classpathEntry.contains(".vaadin.")) {
                return true;
            } else {
                URL url;
                try {
                    url = new URL("file:"
                            + new File(classpathEntry).getCanonicalPath());
                    url = new URL("jar:" + url.toExternalForm() + "!/");
                    JarURLConnection conn = (JarURLConnection) url
                            .openConnection();
                    debug(url.toString());

                    JarFile jarFile = conn.getJarFile();
                    Manifest manifest = jarFile.getManifest();
                    if (manifest != null) {
                        Attributes mainAttributes = manifest
                                .getMainAttributes();
                        if (mainAttributes.getValue("Vaadin-Widgetsets") != null) {
                            return true;
                        }
                        if (mainAttributes.getValue("Vaadin-Stylesheets") != null) {
                            return true;
                        }
                    }
                } catch (MalformedURLException e) {
                    if (debug) {
                        error("Failed to inspect JAR file", e);
                    }
                } catch (IOException e) {
                    if (debug) {
                        error("Failed to inspect JAR file", e);
                    }
                }

                return false;
            }
        }
    }

    /**
     * Recursively add subdirectories and jar files to locations - see
     * {@link #classpathLocations}.
     *
     * @param name
     * @param file
     * @param locations
     */
    private static void include(String name, File file,
            Map<String, URL> locations) {
        if (!file.exists()) {
            return;
        }
        if (!file.isDirectory()) {
            // could be a JAR file
            includeJar(file, locations);
            return;
        }

        if (file.isHidden() || file.getPath().contains(File.separator + ".")) {
            return;
        }

        if (name == null) {
            name = "";
        } else {
            name += ".";
        }

        // add all directories recursively
        File[] dirs = file.listFiles(DIRECTORIES_ONLY);
        for (File dir : dirs) {
            try {
                // add the present directory
                if (!dir.isHidden() && !dir.getPath().contains(
                        File.separator + ".")) {
                    String key = dir.getCanonicalPath() + "/" + name + dir.
                            getName();
                    locations.put(key, new URL("file://" + dir.
                            getCanonicalPath()));
                }
            } catch (Exception ioe) {
                return;
            }
            include(name + dir.getName(), dir, locations);
        }
    }

    /**
     * Add a jar file to locations - see {@link #classpathLocations}.
     *
     * @param name
     * @param locations
     */
    private static void includeJar(File file, Map<String, URL> locations) {
        try {
            URL url = new URL("file:" + file.getCanonicalPath());
            url = new URL("jar:" + url.toExternalForm() + "!/");
            JarURLConnection conn = (JarURLConnection) url.openConnection();
            JarFile jarFile = conn.getJarFile();
            if (jarFile != null) {
                // the key does not matter here as long as it is unique
                locations.put(url.toString(), url);
            }
        } catch (Exception e) {
            // e.printStackTrace();

        }

    }

    /**
     * Find and return the default source directory where to create new
     * widgetsets.
     *
     * Return the first directory (not a JAR file etc.) on the classpath by
     * default.
     *
     * TODO this could be done better...
     *
     * @param classpathEntries
     * @return URL
     */
    public static URL getDefaultSourceDirectory(List<String> classpathEntries) {

        if (debug) {
            debug("classpathLocations values:");
            for (String location : classpathEntries) {
                debug(location);
            }
        }

        Iterator<String> it = classpathEntries.iterator();
        while (it.hasNext()) {
            String entry = it.next();

            File directory = new File(entry);
            if (directory.exists() && !directory.isHidden()
                    && directory.isDirectory()) {
                try {
                    return new URL("file://" + directory.getCanonicalPath());
                } catch (MalformedURLException e) {
                    // ignore: continue to the next classpath entry
                    if (debug) {
                        e.printStackTrace();
                    }
                } catch (IOException e) {
                    // ignore: continue to the next classpath entry
                    if (debug) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return null;
    }

    private static void log(String message) {
        System.out.println(message);
    }

    private static void error(String message, Exception e) {
        System.err.println(message);
        e.printStackTrace();
    }

    private static void debug(String message) {
        if (debug) {
            System.out.println(message);
        }
    }

    static boolean isWidgetset(String gwtModuleName) {
        return gwtModuleName.toLowerCase().contains("widgetset");
    }

    static private void printValidLicense(CvalInfo info, String title,
            String version) {
        String msg = info.getMessage();
        if (msg == null) {
            String key = "evaluation".equals(info.getType()) ? "evaluation"
                    : "valid";
            msg = getErrorMessage(key, title, computeMajorVersion(version),
                    info.getLicensee());
        }
        System.out.println("\n" + LINE + "\n" + msg + "\n" + LINE + "\n");
    }

    static private void printServerUnreachable(String name, String version) {
        System.out.println(LINE
                + "\n"
                + getErrorMessage("unreachable", name,
                        computeMajorVersion(version)) + "\n" + LINE);
    }

    static final int computeMajorVersion(String productVersion) {
        return productVersion == null || productVersion.isEmpty() ? 0
                : Integer.parseInt(productVersion.replaceFirst("[^\\d]+.*$", ""));
    }
    
        static String getErrorMessage(String key, Object... pars) {
        Locale loc = Locale.getDefault();
        ResourceBundle res = ResourceBundle.getBundle(
                CvalChecker.class.getName(), loc);
        String msg = res.getString(key);
        return new MessageFormat(msg, loc).format(pars);
    }


}
