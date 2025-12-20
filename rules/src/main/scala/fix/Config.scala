package parsedRule

import scalafix.v1._
import scala.meta._
import scala.meta.dialects.Scala3
import scala.io.Source._
import pureconfig._
import pureconfig.error._

case class RuleConfig(
    name: String,
    matchAscriptions: Option[Boolean],
    description: Option[String],
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class RulesConfig(rules: List[RuleConfig]) derives ConfigReader

enum LintLevel:
  case Full
  case Default
  case None

object Config:
  def getLintLevel(): LintLevel =
    sys.env.get("LINT_LEVEL") match
      case Some(full) if full.toLowerCase == "full" => LintLevel.Full
      case Some(none) if none.toLowerCase == "none" => LintLevel.None
      case Some(default) if default.toLowerCase == "default" =>
        LintLevel.Default
      case None => LintLevel.Default
      case Some(other) =>
        System.err.println(
          s"Unknown LINT_LEVEL value: $other. Using default level."
        )
        LintLevel.Default

  def parseRulesConfig(): List[CustomRule] =
    val configFile = sys.env.get("RULES_CONF") match
      case None           => ".rewriter.conf"
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
      val matchTree = rule.pattern.parse[Stat] match
        case Parsed.Success(t) => t
        case Parsed.Error(_, msg, _) =>
          throw new Exception(
            s"Could not parse match pattern for rule '${rule.name}': $msg"
          )
      val rewriteTree = rule.rewrite match
        case Some(rp) =>
          rp.parse[Stat] match
            case Parsed.Success(t) => Some(t)
            case Parsed.Error(_, msg, _) =>
              throw new Exception(
                s"Could not parse rewrite pattern for rule '${rule.name}': $msg"
              )
        case None => None
      val matchOptions = MatchOptions(
        matchAscriptions = rule.matchAscriptions.getOrElse(false)
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
