The Event Admin Service Specification, part of the OSGi Compendium
specification, defines a general inter-bundle communication mechanism. The
communication conforms to the popular publish/subscribe paradigm and can be
performed in a synchronous or asysnchronous manner.

The main components in a publish/subscribe communication are:

Event Publisher - sends events or messages related to a specific topic
Event Handler (or Subscriber) - expresses interest in one or more topics and
receives all the messages belonging to such topics.

Events are composed of two attributes:

- a topic defining the nature of the event; topic names are usually arranged in
  a hierarchical namespace, where slashes are used to separate the levels (i.e.
  "org/osgi/framework/FrameworkEvent/STARTED") and
- a set of properties describing the event.

Unfortunately, our custom OSGi implementation does not support all of the OSGi
standard features. As a result, the (few) stock Event Admin Service
implementations don't work correctly with our implementation.

For that reason, in this package we implement a "poor man's" Event Admin Service
implementation adapted to our needs that is synchronous only and does not
perform any routing at the moment.

Hopefully, we'll soon switch to a standard OSGi runtime and we'll be able to use
a stock Event Admin Service implementation.

George Politis