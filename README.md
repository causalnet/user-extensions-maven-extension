# User Extensions Maven Extension

Adds the ability for Maven to load core extensions from a `<user home>/.m2/extensions.xml`,
for _all projects_, similar to [how it works already](https://maven.apache.org/guides/mini/guide-using-extensions.html) 
for project core extensions that are loaded from a project directory's `.mvn/extensions.xml`.

## Purpose - why was this made

Currently, the only way you can load an extension for every project that you run under Maven is:

- Add the extension JAR in `${maven.home}/lib/ext`
- Edit your `MAVEN_OPTS` environment variable and add `-Dmaven.ext.class.path=<path to your extension JAR(s)`

The first option is less than ideal because you need to change you local Maven installation.
Often this is managed by an operating system's package manager and adding JARs into directories
can confuse them.

The second option is less bad, but extensions need to be written to be able
to handle this (dependencies must either be included in their extension JAR or added to maven.ext.class.path as well)
and they are not downloaded automatically.

For projects, it is currently possible in Maven to have a `.mvn/extensions.xml` file 
([described here](https://maven.apache.org/configure.html#mvn-extensions-xml-file)) with 
the project directory, which Maven will handle, download depedencies, etc.  
Unfortunately this only works per-project.  There are some extensions that need to be 
registered for all builds (for example, a new Wagon provider or a custom password decrypter).

Once this extension is registered, you will be able to put an `extensions.xml` file in your
`<user home>/.m2` directory and it will be used to load extensions, but will be used 
every Maven build.

## Requirements

- Maven 3.5.0 or later (tested up to Maven 3.9.0)
- Java 8 or later

## Installation

The extension needs to be downloaded and configured with Maven.

### Downloading

This can be done easily through Maven itself, downloading the extension Maven Central to your
local repository with:

```
mvn dependency:get -Dartifact=au.net.causal.maven.plugins:user-extensions-maven-extension:1.1
```

### Configuring Maven

The easiest and least invasive way of registering the extension is modifying the `MAVEN_OPTS`
environment variable to contain:

```
-javaagent:<your m2 directory>/repository/au/net/causal/maven/plugins/user-extensions-maven-extension/1.1/user-extensions-maven-extension-1.1.jar
```

The agent will patch Maven to support user-extensions every time Maven is run.

## How it works

This extension is registered on the JVM that runs Maven as an instrumentation agent and patches it to support
user-level extensions.  It's a bit of a hack, so it might break for newer versions of Maven
if the internals change too much.   It's been tested to work with Maven versions 3.5.0 to 3.9.0.

That being said, maybe future versions of Maven get this feature built-in and this extension won't be needed any more?

## Enhanced interpolation feature

An optional feature that can be optionally enabled allows properties in your `extensions.xml`
files to be sourced from profiles in `settings.xml`, and extensions to be disabled by
setting their version to the special string 'disabled'.  With this combination, it is possible
to have user-global extensions switched on/off by using Maven profiles on the command line.

To enable enhanced interpolation, add the option `enhanced_interpolation` to the Java agent
command line argument.  This would make your `MAVEN_OPTS` become:

```
-javaagent:<your m2 directory>/repository/au/net/causal/maven/plugins/user-extensions-maven-extension/1.1/user-extensions-maven-extension-1.1.jar=enhanced_interpolation
```

### Should I use enhanced interpolation?

The downside is that it is less compatible with older Maven versions.  Enhanced interpolation
only works down to Maven version 3.8.5 when its 
[interpolation feature](https://issues.apache.org/jira/browse/MNG-7395) 
was introduced, and is more likely to break with newer untested Maven versions because it 
is a bigger patch.  Because your user-global `extensions.xml` will be used for all Maven 
versions, any interpolated properties will not work with old versions and Maven won't start.
So only use this feature if you are sure you will not use Maven versions older than 3.8.5.

### Enhanced interpolation in practice

Example `.m2/extensions.xml`:

```
<extensions>
    <extension>
        <groupId>my.extension.groupid</groupId>
        <artifactId>extension-artifactid</artifactId>
        <version>${my.extension.version}</version>
    </extension>
</extensions>

```

and in `settings.xml`:

```
...
<profile>
    <id>extensions-disabled-by-default</id>
    <activation>
        <activeByDefault>true</activeByDefault>
    </activation>
    <properties>
        <my.extension.version>disabled</my.extension.version>
    </properties>
</profile>
<profile>
    <id>usemyextension</id>
    <properties>
        <my.extension.version>2.1</my.extension.version>
    </properties>
</profile>
...
```

With these you will be able to enable that particular extension only when the 
profile 'usemyextension' is turned on in builds, which is simple as running 
Maven with `-Pusemyextension`.


## Building

To build the project, run:

```
mvn clean install
```
