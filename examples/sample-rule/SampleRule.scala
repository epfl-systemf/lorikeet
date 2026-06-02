//> using scala 3.7.0
//> using dep ch.epfl.systemf::lorikeet:0.1.0-SNAPSHOT
//> using resourceDir ./res
//> using publish.organization ch.epfl.systemf
//> using publish.name sample-rule

package fix

import lorikeet.rules.RuleFromResource

class SampleRule extends RuleFromResource(
  name = "SampleRule",
  resourcePath = "sample_rule.lorikeet.conf"
)
