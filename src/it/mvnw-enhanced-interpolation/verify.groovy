//Passwords are generated by antrun goal in the build, and will only work if the servers extension is loaded
//The servers extension is only loaded because our user-extensions extension loads it from .m2/extensions.xml

//Each different Maven version tested generated a different file name, check each one

['3.9.0', '3.8.7', '3.8.5'].each { mavenVersion ->
    File serverPasswordsFile = new File(basedir, "target/server-passwords-${mavenVersion}.properties")
    Properties serverPasswords = new Properties()
    serverPasswordsFile.withInputStream {
        serverPasswords.load(it)
    }

    assert serverPasswords.testServerUser == 'test-user'
    assert serverPasswords.testServerPassword == 'test-password'
    assert serverPasswords.mavenVersion == mavenVersion
}

return