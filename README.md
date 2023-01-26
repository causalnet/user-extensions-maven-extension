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

- Maven 3.x
- Java 8 or later

## Installation

The extension needs to be downloaded and configured with Maven.

### Downloading

This can be done easily through Maven itself, downloading the extension Maven Central to your
local repository with:

```
mvn dependency:get -Dartifact=au.net.causal.maven.plugins:user-extensions-maven-extension:1.0
```

### Configuring Maven

The easiest and least invasive way of registering the extension is modifying the `MAVEN_OPTS`
environment variable to contain:

```
-javaagent:<your m2 directory>/repository/au/net/causal/maven/plugins/user-extensions-maven-extension/1.0/user-extensions-maven-extension-1.0.jar
```

The agent will patch Maven to support user-extensions every time Maven is run.

## How it works

This extension is registered on the JVM that runs Maven as an instrumentation agent and patches it to support
user-level extensions.  It's a bit of a hack, so it might break for newer versions of Maven
if the internals change too much.   It's been tested to work with Maven 3.8.7.  

That being said, maybe future versions of Maven get this feature built-in and this extension won't be needed any more?

## Building

To build the project, run:

```
mvn clean install
```
