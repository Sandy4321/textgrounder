//  GroupTwitterPull.scala
//
//  Copyright (C) 2012 Stephen Roller, The University of Texas at Austin
//  Copyright (C) 2012 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.textgrounder.preprocess

import java.io._

import org.apache.commons.logging.LogFactory
import org.apache.log4j.{Level=>JLevel,_}
import org.apache.hadoop.fs.{FileSystem => HFileSystem, Path}

import com.nicta.scoobi.Scoobi._
import com.nicta.scoobi.testing.HadoopLogFactory
// import com.nicta.scoobi.application.HadoopLogFactory

import opennlp.textgrounder.util.argparser._
import opennlp.textgrounder.util.osutil._
import opennlp.textgrounder.util.collectionutil._
import opennlp.textgrounder.util.printutil._

class ScoobiProcessFilesParams(ap: ArgParser) {
  var debug = ap.flag("debug",
    help="""Output debug info about tweet processing/acceptance.""")
  var debug_file = ap.option[String]("debug-file",
    help="""File to write debug info to, instead of stderr.""")
//     var use_jerkson = ap.flag("use-jerkson",
//       help="""Use Jerkson instead of Lift to parse JSON.""")
  var input = ap.positional[String]("INPUT",
    help = "Source directory to read files from.")
  var output = ap.positional[String]("OUTPUT",
    help = "Destination directory to place files in.")
}

trait ScoobiProcessFilesShared {

  def progname: String
  val operation_category: String
  def full_operation_category = progname + "." + operation_category

  var lineno = 0
  lazy val logger = LogFactory.getLog(full_operation_category)

  def warning(line: String, fmt: String, args: Any*) {
    logger.warn("Line %d: %s: %s" format
      (lineno, fmt format (args: _*), line))
  }

  def bump_counter(counter: String) {
    incrCounter(full_operation_category, counter)
  }

  /**
   * A class used internally by `error_wrap`.
   */
  private class ErrorWrapper {
    // errprint("Created an ErrorWrapper")
    var lineno = 0
  }

  /**
   * This is used to keep track of the line number.  Theoretically we should
   * be able to key off of `fun` itself but this doesn't actually work,
   * because a new object is created each time to hold the environment of
   * the function.  However, using the class of the function works well,
   * because internally each anonymous function is implemented by defining
   * a new class that underlyingly implements the function.
   */
  private val wrapper_map =
    defaultmap[Class[_], ErrorWrapper](new ErrorWrapper, setkey = true)

  /**
   * Wrapper function used to catch errors when doing line-oriented
   * (or record-oriented) processing.  It is passed a value (typically,
   * the line or record to be processed), a function to process the
   * value, and a default value to be returned upon error.  If an error
   * occurs during execution of the function, we log the line number,
   * the value that triggered the error, and the error itself (including
   * stack trace), and return the default.
   *
   * We need to create internal state in order to track the line number.
   * This function is able to handle multiple overlapping or nested
   * invocations of `error_wrap`, keyed (approximately) on the particular
   * function invoked.
   *
   * @param value Value to process
   * @param fun Function to use to process the value
   * @param default Default value to be returned during error
   */
  def error_wrap[T, U](value: T, default: => U)(fun: T => U) = {
    // errprint("error_wrap called with fun %s", fun)
    // errprint("class is %s", fun.getClass)
    val wrapper = wrapper_map(fun.getClass)
    // errprint("got wrapper %s", wrapper)
    try {
      wrapper.lineno += 1
      fun(value)
    } catch {
      case e: Exception => {
        logger.warn("Line %d: %s: %s\n%s" format
          (wrapper.lineno, e, value, stack_trace_as_string(e)))
        default
      }
    }
  }
}

abstract class ScoobiProcessFilesApp[ParamType <: ScoobiProcessFilesParams]
    extends ScoobiApp with ScoobiProcessFilesShared {

  def create_params(ap: ArgParser): ParamType
  val operation_category = "MainApp"
  def output_command_line_parameters(arg_parser: ArgParser) {
    // Output using errprint() rather than logger() so that the results
    // stand out more.
    errprint("")
    errprint("Non-default parameter values:")
    for (name <- arg_parser.argNames) {
      if (arg_parser.specified(name))
        errprint("%30s: %s" format (name, arg_parser(name)))
    }
    errprint("")
    errprint("Parameter values:")
    for (name <- arg_parser.argNames) {
      errprint("%30s: %s" format (name, arg_parser(name)))
      //errprint("%30s: %s" format (name, arg_parser.getType(name)))
    }
    errprint("")
  }

  def init_scoobi_app() = {
    initialize_osutil()
    val ap = new ArgParser(progname)
    // This first call is necessary, even though it doesn't appear to do
    // anything.  In particular, this ensures that all arguments have been
    // defined on `ap` prior to parsing.
    create_params(ap)
    // Here and below, output using errprint() rather than logger() so that
    // the basic steps stand out more -- when accompanied by typical logger
    // prefixes, they easily disappear.
    errprint("Parsing args: %s" format (args mkString " "))
    ap.parse(args)
    val Opts = create_params(ap)
    enableCounterLogging()
    if (Opts.debug) {
      HadoopLogFactory.setQuiet(false)
      HadoopLogFactory.setLogLevel(HadoopLogFactory.TRACE)
      LogManager.getRootLogger().setLevel(JLevel.DEBUG.asInstanceOf[JLevel])
    }
    if (Opts.debug_file != null)
      set_errout_file(Opts.debug_file)
    output_command_line_parameters(ap)
    Opts
  }

  def finish_scoobi_app(Opts: ParamType) {
    errprint("All done with everything.")
    errprint("")
    output_resource_usage()
  }

  def rename_output_files(fs: HFileSystem, dir: String, corpus_name: String,
    suffix: String) {
    // Rename output files appropriately
    errprint("Renaming output files ...")
    val globpat = "%s/*-r-*" format dir
    for (file <- fs.globStatus(new Path(globpat))) {
      val path = file.getPath
      val basename = path.getName
      val newname = "%s/%s-%s-%s.txt" format (
        dir, corpus_name, basename, suffix)
      errprint("Renaming %s to %s" format (path, newname))
      fs.rename(path, new Path(newname))
    }
  }
}

