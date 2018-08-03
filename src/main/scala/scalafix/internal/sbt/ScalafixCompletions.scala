package scalafix.internal.sbt

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

import scala.util.control.NonFatal

import sbt.complete._
import sbt.complete.DefaultParsers._
import sbt.internal.sbtscalafix.JLineAccess

object ScalafixCompletions extends ScalafixCompletionsComponent with JLineAccess

trait ScalafixCompletionsComponent { self: JLineAccess =>
  private type P = Parser[String]
  private val space: P = token(Space).map(_.toString)
  private val string: P = StringBasic
  private def repsep(rep: P, sep: String): P =
    DefaultParsers.repsep(rep, sep: P).map(_.mkString(sep))
  private def arg(key: String, value: P): P =
    (key ~ space ~ value).map { case a ~ _ ~ c => a + " " + c }
  private def arg(key: String, shortKey: String, value: P): P =
    ((key | hide(shortKey)) ~ space ~ value).map {
      case a ~ _ ~ c => a + " " + c
    }

  private def mapOrFail[S, T](p: Parser[S])(f: S => T): Parser[T] =
    p.flatMap { s =>
      try {
        success(f(s))
      } catch {
        case NonFatal(e) => failure(e.toString)
      }
    }

  private def uri(protocol: String) =
    token(protocol + ":") ~> NotQuoted.map(x => s"$protocol:$x")
  private def filepathParser(cwd: Path): P = {
    def toAbsolutePath(path: Path, cwd: Path): Path = {
      if (path.isAbsolute) path
      else cwd.resolve(path)
    }.normalize()

    // Extend FileExamples to tab complete when the prefix is an absolute path or `..`
    class AbsolutePathExamples(cwd: Path, prefix: String = "")
        extends FileExamples(cwd.toFile, prefix) {
      override def withAddedPrefix(addedPrefix: String): FileExamples = {

        val nextPrefix =
          if (addedPrefix.startsWith(".")) addedPrefix
          else prefix + addedPrefix
        val (b, p) = AbsolutePathCompleter.mkBase(nextPrefix, cwd)
        new AbsolutePathExamples(b, p)
      }
    }
    object AbsolutePathCompleter {
      def mkBase(prefix: String, fallback: Path): (Path, String) = {
        val path = toAbsolutePath(Paths.get(prefix), fallback)
        if (prefix.endsWith(File.separator)) path -> ""
        else {
          if (path.getFileName != null)
            path.getParent -> path.getFileName.toString
          else fallback -> ""
        }
      }
    }

    string
      .examples(new AbsolutePathExamples(cwd))
      .map { f =>
        toAbsolutePath(Paths.get(f), cwd).toString
      }
  }

  private val namedRule: P = {
    val termWidth = terminalWidth
    token(
      NotQuoted,
      TokenCompletions.fixed(
        (seen, _) => {
          val candidates = ScalafixRuleNames.all.filter {
            case (name, _) =>
              name.startsWith(seen)
          }
          val maxRuleNameLen =
            candidates.map(_._1.length).reduceOption(_ max _).getOrElse(0)
          val rules = candidates
            .map {
              case (name, description) =>
                val spaces = " " * (maxRuleNameLen - name.length)
                new Token(
                  display = s"$name$spaces -- $description".take(termWidth),
                  append = name.stripPrefix(seen)
                )
            }
            .toSet[Completion]
          Completions.strict(rules)
        }
      )
    )
  }
  private def gitDiffParser(cwd: Path): P = {
    val jgitCompletion = new JGitCompletion(cwd)
    token(
      NotQuoted,
      TokenCompletions.fixed(
        (seen, level) => {
          val last20Commits =
            jgitCompletion.last20Commits
              .filter { case (_, sha1) => sha1.startsWith(seen) }
              .zipWithIndex
              .map {
                case ((log, sha1), i) => {
                  val j = i + 1
                  val idx = if (j < 10) " " + j.toString else j.toString
                  new Token(
                    display = s"|$idx| $log",
                    append = sha1.stripPrefix(seen)
                  )
                }
              }
              .toSet

          val branchesAndTags =
            jgitCompletion.branchesAndTags
              .filter(info => info.startsWith(seen))
              .map { info =>
                new Token(display = info, append = info.stripPrefix(seen))
              }
              .toSet

          Completions.strict(last20Commits ++ branchesAndTags)
        }
      )
    )
  }

  def hide(p: P): P = p.examples()

  def parser(cwd: Path, compat: Boolean): Parser[Seq[String]] = {
    val pathParser: P = token(filepathParser(cwd))
    val pathRegexParser: P = mapOrFail(pathParser) { regex =>
      Pattern.compile(regex); regex
    }
    val classpathParser: P = repsep(pathParser, File.pathSeparator)
    val fileRule: P = (token("file:") ~ pathParser.map("file:" + _)).map {
      case a ~ b => a + b
    }
    val ruleParser =
      namedRule | fileRule | uri("github") | uri("replace") |
        uri("http") | uri("https") | uri("scala")

    val classpath: P = arg("--classpath", classpathParser)
    val autoClasspath: P = "--auto-classpath"
    val config: P = arg("--config", "-c", pathParser)
    val diff: P = "--diff"
    val diffBase: P = arg("--diff-base", gitDiffParser(cwd))
    val exclude: P = arg("--exclude", pathRegexParser)
    val files: P = arg("--files", "-f", pathParser)
    val nonInteractive: P = "--non-interactive"
    val outFrom: P = arg("--out-from", pathRegexParser)
    val outTo: P = arg("--out-to", pathRegexParser)
    val rules: P = arg("--rules", "-r", ruleParser)
    val sourceroot: P = arg("--sourceroot", pathParser)
    val stdout: P = "--stdout"
    val test: P = "--test"
    val toolClasspath: P = arg("--tool-classpath", classpathParser)
    val help: P = "--help"
    val version: P = "--version" | hide("-v")
    val verbose: P = "--verbose"

    val base =
      classpath |
        autoClasspath |
        config |
        diff |
        diffBase |
        exclude |
        files |
        nonInteractive |
        outFrom |
        outTo |
        rules |
        sourceroot |
        stdout |
        test |
        toolClasspath |
        help |
        version |
        verbose

    if (compat) {
      (token(Space) ~> token(ruleParser)).* <~ SpaceClass.*
    } else {
      ((token(Space) ~> base).* ~ (token(Space) ~> filepathParser(cwd)).?).map {
        case a ~ b =>
          (a ++ b.toSeq).flatMap(_.split(" ").toSeq)
      }
    }
  }
}
