package lorikeet.core

import scala.meta._
import scala.util.matching.Regex
import pureconfig._
import pureconfig.error._

case class RuleConfig(
    name: String,
    matchAscriptions: Option[Boolean],
    matchFqn: Option[Boolean],
    matchBlocks: Option[Boolean],
    onlyPackages: Option[List[String]],
    stableBooleanBindings: Option[List[String]],
    description: Option[String],
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class TokenRuleConfig(
    name: String,
    onlyPackages: Option[List[String]],
    targetKind: Option[String],
    description: Option[String],
    pattern: String,
    rewrite: Option[String]
) derives ConfigReader
case class RulesConfig(
    maxRewrites: Option[Int],
    rules: List[RuleConfig],
    tokenRules: Option[List[TokenRuleConfig]]
) derives ConfigReader

case class ParsedConfig(
    maxRewrites: Int,
    rules: List[CustomRule],
    tokenRules: List[TokenRule]
)

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

  def parseConfig(config: Option[String]): ParsedConfig =
    val configResults: Either[ConfigReaderFailures, RulesConfig] =
      config match
        case Some(configStr) =>
          ConfigSource.string(configStr).load[RulesConfig]
        case None =>
          val configFile = sys.env.get("RULES_CONF") match
            case None           => ".lorikeet.conf"
            case Some(filename) => filename
          ConfigSource.file(configFile).load[RulesConfig]

    val loadedConfig = configResults match
      case Right(r) => r
      case Left(e) =>
        val source = config match
          case Some(_) => "config string"
          case None =>
            s"configuration file: ${sys.env.get("RULES_CONF").getOrElse(".lorikeet.conf")}"
        throw new Exception(
          s"Could not read rules from $source. " +
            s"Error: ${e.prettyPrint()}"
        )

    val maxRewrites = loadedConfig.maxRewrites.getOrElse(100)
    if maxRewrites <= 0 then
      throw new Exception("max-rewrites must be greater than zero.")

    val ruleTrees: List[CustomRule] = loadedConfig.rules.map { rule =>
      val matchTree = parseCode(rule.pattern, rule.name, "match pattern")

      val rewriteTree = rule.rewrite match
        case Some(rp) => Some(parseCode(rp, rule.name, "rewrite pattern"))
        case None     => None
      val matchOptions = MatchOptions(
        matchAscriptions = rule.matchAscriptions.getOrElse(false),
        matchFqn = rule.matchFqn.getOrElse(true),
        matchBlocks = rule.matchBlocks.getOrElse(false),
        onlyPackages = rule.onlyPackages
      )
      CustomRule(
        rule.name,
        matchTree,
        rewriteTree,
        rule.rewrite,
        matchOptions,
        rule.description,
        rule.stableBooleanBindings.getOrElse(Nil)
      )
    }

    val tokenRules = loadedConfig.tokenRules.getOrElse(Nil).map { rule =>
      val pattern =
        try Regex(rule.pattern)
        catch
          case error: java.util.regex.PatternSyntaxException =>
            throw new Exception(
              s"Could not parse token pattern for rule '${rule.name}': ${error.getDescription}"
            )
      TokenRule(
        rule.name,
        pattern,
        rule.rewrite,
        rule.onlyPackages,
        rule.targetKind.map { kind =>
          if !Set("parameter", "function", "value", "function-argument").contains(kind) then
            throw new Exception(s"Unknown target-kind '$kind' in rule '${rule.name}'")
          kind
        },
        rule.description
      )
    }

    ParsedConfig(maxRewrites, ruleTrees, tokenRules)

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

  def parseSource(code: String, filename: String): Source =
    val input = new scala.meta.inputs.Input.VirtualFile(filename, code)
    given scala.meta.Dialect = scala.meta.dialects.Scala3
    input.parse[Source] match
      case Parsed.Success(t) => t
      case Parsed.Error(_, msgScala3, _) =>
        given scala.meta.Dialect = scala.meta.dialects.Scala213
        input.parse[Source] match
          case Parsed.Success(t) => t
          case Parsed.Error(_, msgScala2, _) =>
            throw new Exception(
              s"Lorikeet produced code that could not be parsed. " +
                s"Scala 3 error: $msgScala3. Scala 2 error: $msgScala2"
            )
