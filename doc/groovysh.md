Groovy includes a command-line utility called “groovysh” that is simply
a wrapper around a Java class called InteractiveShell, which can be
embedded in virtually any Java application. InteractiveShell’s
constructor can be given an InputStream for input, a PrintStream for
output (and errors), and a Binding object, which is a mapping between
tokens and Java objects that will be made available to the interpreter.

By simply wiring this class up with some standard Java networking code,
the interpreter can be launched from inside the bridge, providing an
environment that we can telnet into (or potentially ssh into) and
execute Groovy code accessing all of the bridge internals. A simple
bundle activator does that.

Once the groovy shell is up and running we can connect to it and run
some code to display or modify the application’s state, call functions,
create objects, etc. Here's how one can use it:

```
gp@nu:~$ nc localhost 6263
Groovy Shell (2.3.7, JVM: 1.7.0_65)
Type ':help' or ':h' for help.
-------------------------------------------------------------------------------
groovy:000> refs =
context.getServiceReferences("org.jitsi.videobridge.Videobridge", null)
===>
[org.jitsi.impl.osgi.framework.ServiceRegistrationImpl$ServiceReferenceImpl@cee8427

groovy:000> videobridge = context.getService(refs[0])
===> org.jitsi.videobridge.Videobridge@18805d97

groovy:000> videobridge.getConferenceCount()
===> 3
```

Groovy is licensed under the Apache 2 license. The additional
dependencies required for this experiment are

jline (180 KB)
groovy (4.3 MB)
groovy-groovysh (458 KB)
