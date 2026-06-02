# Sample Standalone Lorikeet Rule

This is an example of a Lorikeet rule that can be packaged and published
like a standalone Scalafix rule.

The `SampleRule` class extends `RuleFromResource`, which allows you to define
a Lorikeet rule using a configuration file. The `name` parameter specifies the
name of the rule, and the `resourcePath` parameter points to the location of the configuration file within the classpath.

You need the following:

- A `SampleRule` class that extends `RuleFromResource` and specifies the name and resource path of the rule.
- A `sample_rule.lorikeet.conf` file that contains the actual rule configuration, in the resources directory of your project.
- A `META_INF/services/scalafix.v1.Rule` file that lists the fully qualified name of the `SampleRule` class, in the resources directory of your project.

Note the following Scala CLI annotations in the `SampleRule` class:

```scala
//> using dep ch.epfl.systemf::lorikeet:0.1.0-SNAPSHOT
//> using resourceDir ./res
```

They specify the dependency on the Lorikeet library and the location of the resource files, respectively. Make sure to adjust these paths according to your project structure.

Of course, you could also use an SBT project or otherwise, this is just a minimal example to show how to create a standalone Lorikeet rule.

We can publish the rule locally using `scala-cli --power publish local .` and then use it in another project by adding the dependency `ch.epfl.systemf::sample-rule:0.1.0-SNAPSHOT` to the build file of that project.
