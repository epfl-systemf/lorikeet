# Lorikeet Core

Lorikeet is built on top of Scalafix and is essentially a Scalafix rule. See the [Scalafix Developer Guide](https://scalacenter.github.io/scalafix/docs/developers/setup.html).

## Run Tests

```bash
sbt "tests / test"
```

To run tests only for specific versions:

```bash
sbt "testsTarget2_12 / test"
sbt "testsTarget2_13 / test"
sbt "testsTarget3 / test"
```

To run tests who's names contain a specific substring:

```bash
sbt "tests / testOnly * -- -z <substring>"
```

## Publish Locally

```bash
sbt "lorikeet / publishLocal"
```
