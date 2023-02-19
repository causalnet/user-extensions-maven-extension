# Change Log
### 1.2
unreleased

- Add ability to patch IntelliJ's Maven server so the user extensions agent always runs
  when doing things like resolving dependencies (important for extensions that 
  add additional decryptors for example)

### 1.1
2023-02-12

- Fix extension breaking when running under Maven wrapper due to classloader isolation
  differences
- Add enhanced interpolation feature that allows properties to be sourced from profiles in 
  settings.xml
- Improve backwards compatibility back to Maven 3.3.1 
- Allow user's extensions.xml file location to be overridden by system property

### 1.0
2023-01-26

- Initial version
