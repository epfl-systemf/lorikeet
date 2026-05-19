package lorikeet

import scala.meta._
import scala.io.Source._
import pureconfig._
import pureconfig.error._

case class RuleConfig(
    name: String,
    matchAscriptions: Option[Boolean],
    matchQualifiedNamesBySymbol: Option[Boolean],
    onlyPackages: Option[List[String]],
    description: Option[String],
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class RulesConfig(rules: List[RuleConfig]) derives ConfigReader

enum LintLevel:
  case Full
  case Medium
  case None

object Config:
  def getLintLevel(): LintLevel =
    sys.env.get("LINT_LEVEL") match
      case Some(full) if full.toLowerCase == "full"       => LintLevel.Full
      case Some(none) if none.toLowerCase == "none"       => LintLevel.None
      case Some(medium) if medium.toLowerCase == "medium" => LintLevel.Medium
      case None                                           => LintLevel.Full
      case Some(other) =>
        System.err.println(
          s"Unknown LINT_LEVEL value: $other. Using highest level."
        )
        LintLevel.Full

  def parseRulesConfig(): List[CustomRule] =
    val configFile = sys.env.get("RULES_CONF") match
      case None           => ".lorikeet.conf"
      case Some(filename) => filename

    val configResults: Either[ConfigReaderFailures, RulesConfig] =
      ConfigSource.file(configFile).load[RulesConfig]

    val rules: List[RuleConfig] = configResults match
      case Right(r) => r.rules
      case Left(e) =>
        throw new Exception(
          s"Could not read rules from configuration file: $configFile. " +
            s"Error: ${e.prettyPrint()}"
        )

    val ruleTrees: List[CustomRule] = rules.map { rule =>
      val matchTree = parseCode(rule.pattern, rule.name, "match pattern")

      val rewriteTree = rule.rewrite match
        case Some(rp) => Some(parseCode(rp, rule.name, "rewrite pattern"))
        case None     => None
      val matchOptions = MatchOptions(
        matchAscriptions = rule.matchAscriptions.getOrElse(false),
        matchQualifiedNamesBySymbol =
          rule.matchQualifiedNamesBySymbol.getOrElse(false),
        onlyPackages = rule.onlyPackages
      )
      CustomRule(
        rule.name,
        matchTree,
        rewriteTree,
        matchOptions,
        rule.description
      )
    }

    ruleTrees

  def parseCode(code: String, ruleName: String, codeType: String): Stat =
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    code.parse[Stat] match
      case Parsed.Success(t) => t
      case Parsed.Error(_, msgScala3, _) =>
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        code.parse[Stat] match
          case Parsed.Success(t) => t
          case Parsed.Error(_, msgScala2, _) =>
            throw new Exception(
              s"Could not parse $codeType for rule '$ruleName'. " +
                s"Scala 3 error: $msgScala3. Scala 2 error: $msgScala2"
            )
