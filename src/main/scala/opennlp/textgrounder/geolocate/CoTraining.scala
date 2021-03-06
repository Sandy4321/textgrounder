///////////////////////////////////////////////////////////////////////////////
//  CoTraining.scala
//
//  Copyright (C) 2014 Ben Wing, The University of Texas at Austin
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

package opennlp.textgrounder
package geolocate

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.{Left, Right}
import scala.util.control.Breaks._

import java.io._
import java.nio.file.Files

import opennlp.fieldspring.tr.app._
import opennlp.fieldspring.tr.app.BaseApp.CORPUS_FORMAT
import opennlp.fieldspring.tr.resolver._
import WeightedMinDistResolver.DOCUMENT_COORD
import opennlp.fieldspring.tr.text._
import opennlp.fieldspring.tr.text.prep._
import opennlp.fieldspring.tr.text.io._
import opennlp.fieldspring.tr.util.{TextUtil, TopoUtil}

import gridlocate._

import util.collection._
import util.debug._
import util.error.unsupported
import util.io.localfh
import util.math._
import util.metering._
import util.print.{errprint, errout}
import util.spherical._
import util.textdb._

/*
 * How to hook co-training into TextGrounder:
 *
 * We want co-training to be another meta-algorithm like reranking.
 * Reranking hooks into create_ranker() where a "ranker" is a document
 * geolocator that returns a ranking over cells for a given document.
 *
 * The current code in TextGrounder to read in a corpus doesn't read in
 * the raw text. In fact, that isn't normally available in the corpora.
 * We need to do this differently. FieldSpring does have a Corpus class
 * made up of documents, sentences and tokens, where tokens can be toponyms.
 * We might be able to hook into that code to do the reading; or we do
 * it ourselves and then create a parallel FieldSpring corpus from it.
 * We will definitely also need to convert the raw-text corpus into
 * TextGrounder documents (GridDocs). That is done using
 * create_ranker_from_document_streams(), which takes a set of streams
 * in raw format (basically, a row direct from a TextDB database). We will
 * need to fake the row and corresponding schema. This needs to be done
 * within GeolocateDriver because the driver stores various parameters
 * that control operation of the grid/ranker creation.
 */

// abstract class Token {
//   def word: String
// }
// 
// case class Word(word: String) extends Token(word)
// case class Toponym(word: String, candidates: Seq[Candidate],
//   resolved: Option[Candidate]) extends Token(word)
// class Candidate(location: String, coord: Co)
// 
// class Doc(text: IndexedSeq[Token], lm: LangModel, label: Option[SphereCoord],
//   prob: Double) {
// }

class CDoc(val fsdoc: Document[Token], var score: Double) {
  val schema = new Schema(Iterable("title", "id", "coord", "unigram-counts"),
    Map("corpus-type" -> "generic", "split" -> "training"))

  /**
   * Convert the stored FieldSpring-style documents to TextGrounder raw
   * documents so they can be used to create a TextGrounder document
   * geolocator (ranker). FIXME: This seems hackish, especially having to
   * convert the words into a serialized string which is then parsed.
   */
  def get_rawdoc = {
    val words = intmap[String]()
    for (sent <- fsdoc; token <- sent.getTokens; form = token.getForm;
         if form != "")
      words(form) += 1
    val doc_gold = fsdoc.getGoldCoord
    val coord =
      if (doc_gold == null) "" else
        "%s,%s" format (doc_gold.getLatDegrees, doc_gold.getLngDegrees)
    val word_field = Encoder.count_map(words)
    // errprint("Words: %s", word_field)
    val props = IndexedSeq(
      if (fsdoc.title != null) fsdoc.title else fsdoc.getId,
      fsdoc.getId, coord, word_field)
    // FIXME! Set importance weight and domain properly
    RawDoc(Row(schema, props), "", 1.0)
  }

  def get_doc_status_rawdoc = {
    val rawdoc = get_rawdoc
    DocStatus(localfh, "unknown", 1, Some(rawdoc), "processed", "", "")
  }

  val doc_counter_tracker =
    new DocCounterTracker[GridDoc[SphereCoord]]("unknown", null)
  /**
   * Convert a FieldSpring-style document into a TextGrounder document.
   */
  def get_grid_doc(ranker: GridRanker[SphereCoord]) = {
    // Convert to a raw document
    val rawdoc = get_doc_status_rawdoc
    // Convert to a GridDoc
    val rddocstat = ranker.grid.docfact.raw_document_to_document_status(
      rawdoc, skip_no_coord = false, note_globally = false)
    val docstat = rddocstat map_all { case (rawdoc, griddoc) => griddoc }
    val maybe_griddoc = doc_counter_tracker.handle_status(docstat)
    // raw_document_to_document_status() doesn't compute the backoff
    // stats; we need to do this
    maybe_griddoc.foreach { _.lang_model.finish_after_global() }
    maybe_griddoc
  }

  /**
   * Return closest distance between document location and any candidate of
   * any toponym, or -1 if no toponym candidates.
   */
  def error_distance_from_nearest_toponym_candidate = {
    var mindist = Double.MaxValue
    val doc_gold = fsdoc.getGoldCoord
    for (sent <- fsdoc) {
      for (toponym <- sent.getToponyms) {
        for (cand <- toponym.getCandidates) {
          val location = cand.getRegion.getCenter
          val dist = location.distanceInKm(doc_gold)
          if (dist < mindist)
            mindist = dist
        }
      }
    }
    if (mindist == Double.MaxValue) -1.0
    else mindist
  }

  /**
   * Return closest distance between document location and any predicted
   * toponym, or -1 if no toponyms.
   */
  def error_distance_from_nearest_predicted_toponym = {
    var mindist = Double.MaxValue
    val doc_gold = fsdoc.getGoldCoord
    for (sent <- fsdoc) {
      for (toponym <- sent.getToponyms) {
        if (toponym.hasSelected) {
          val location = toponym.getSelected.getRegion.getCenter
          val dist = location.distanceInKm(doc_gold)
          if (dist < mindist)
            mindist = dist
        } else {
          errprint("Toponym %s has no prediction", toponym.getForm)
        }
      }
    }
    if (mindist == Double.MaxValue) -1.0
    else mindist
  }

  /**
   * Return true if one of the toponyms in the document has a candidate that
   * is close to the resolved document location.
   */
  def toponym_candidate_near_location(threshold: Double): Boolean = {
    val errdist = error_distance_from_nearest_toponym_candidate
    if (errdist < 0 || errdist > threshold)
      false
    else
      true
  }

  def debug_print(prefix: String = "") {
    FieldSpringCCorpus.debug_print_doc(fsdoc, prefix)
  }
}


/**
 * A corpus that holds documents. We need the following types:
 *
 * 1. A corpus read from a textdb, such as the original Wikipedia corpus.
 *    You can't iterate through the text of this corpus but it has a
 *    document geolocator and document-level coordinates.
 * 2. A corpus read from a FieldSpring corpus, without attached document-level
 *    coordinates. We need to be able to select portions of the total
 *    documents to be iterated through.
 * 3. A corpus read from a FieldSpring corpus, with attached document-level
 *    coordinates.
 * 4. A corpus created from documents built up from individual words, with
 *    attached document-level coordinates.
 *
 * The operations required are:
 *
 * 1. Iterate through the documents of the corpus, multiple times.
 * 2. Remove documents from the corpus, especially corpus type #2/#3, and
 *    add those documents to another corpus.
 * 3. Create documents from individual words and add them to corpus type #4.
 * 4. Generate a document geolocator from at least #1 (which already comes
 *    with one) and #4.
 * 5. Create a corpus for doing toponym resolution (a StoredCorpus) from
 *    corpus types #2/#3.
 *
 * To implement 2 and 3, we read the documents into a StoredCorpus and then
 * record the documents in a LinkedHashSet, so we can add/remove documents
 * while preserving their order. It's necessary to use a StoredCorpus because
 * the documents in a DocumentSource can be iterated through only once, and
 * the words in such a document iterated through only once.
 * preserving their order. We create another implementation of DocumentSource
 * that iterates through these documents, so that we can create a
 * StoredCorpus from it to do toponym resolution on. (FIXME: Is this necessary?
 * Maybe we can create our own implementation of StoredCorpus, since we
 * do store the documents.)
 */
abstract class CCorpus(
) {
  def docs: Iterable[CDoc]
  def to_docgeo_ranker(driver: GeolocateDriver): GridRanker[SphereCoord]
}

class TextGrounderCCorpus(ranker: GridRanker[SphereCoord]) extends CCorpus {
  def docs = unsupported()
  def to_docgeo_ranker(driver: GeolocateDriver) = ranker
}

object FieldSpringCCorpus {
  def apply(corpus: StoredCorpus) = {
    val ccorp = new FieldSpringCCorpus
    for (doc <- corpus)
      ccorp += new CDoc(doc.asInstanceOf[Document[Token]], 0.0)
    ccorp
  }

  def debug_print_doc[T <: Token](fsdoc: Document[T], prefix: String = "") {
    errprint("%sDocument %s [%s pred=%s]:", prefix, fsdoc.getId,
      fsdoc.getGoldCoord, fsdoc.getSystemCoord)
    for (sent <- fsdoc) {
      val words = for (token <- sent; form = token.getForm) yield {
        if (token.isToponym) {
          val toponym = token.asInstanceOf[Toponym]
          if (toponym.getAmbiguity == 0)
            "[%s amb=0]" format form
          else {
            val gold =
              if (toponym.hasGold)
                " %s" format toponym.getGold.getRegion.getCenter
              else
                ""
            val pred =
              if (toponym.hasSelected)
                " pred=%s" format toponym.getSelected.getRegion.getCenter
              else
                ""
            "[%s%s%s]" format (form, gold, pred)
          }
        } else
          form
      }
      errprint("  %s%s", prefix, words mkString " ")
    }
  }

  def debug_print_corpus(corpus: StoredCorpus, prefix: String = "") {
    for ((doc, index) <- corpus.zipWithIndex) {
      debug_print_doc(doc, "%s#%s: " format (prefix, index + 1))
    }
  }
}

class FieldSpringCCorpus extends CCorpus {
  val docs = mutable.LinkedHashSet[CDoc]()

  def this(newdocs: Iterable[CDoc]) {
    this()
    docs ++= newdocs
  }

  def to_stored_corpus = {
    val fscorpus = Corpus.createStoredCorpus
    fscorpus.addSource(new CCorpusDocumentSource(this))
    fscorpus.load
    fscorpus
  }

  def filter(fn: CDoc => Boolean) =
    new FieldSpringCCorpus(docs.filter(fn))

  def +=(x: CDoc) {
    docs += x
  }

  def -=(x: CDoc) {
    docs -= x
  }

  def is_empty = docs.isEmpty

  def size = docs.size

  /**
   * Convert the stored FieldSpring-style documents to TextGrounder raw
   * documents. FIXME: This seems hackish, especially having to convert the
   * words into a serialized string which is then parsed.
   */
  def get_rawdocs: Iterable[RawDoc] = {
    for (doc <- docs.toSeq) yield
      doc.get_rawdoc
  }

  /**
   * Convert the stored FieldSpring-style documents to TextGrounder raw
   * documents surrounded by DocStatus objects so they can be used to create
   * a TextGrounder document geolocator (ranker). FIXME: This seems hackish,
   * especially having to convert the words into a serialized string which is
   * then parsed.
   */
  def get_doc_status_rawdocs: Iterable[DocStatus[RawDoc]] = {
    for (doc <- docs.toSeq) yield
      doc.get_doc_status_rawdoc
  }

  /**
   * Write the text of the corpus to FILE in the format used in
   * TextGrounder and required by WISTR.
   */
  def write_corpus_text(file: PrintStream) = {
    for (doc <- docs) {
      val fsdoc = doc.fsdoc
      file.println("Article title: %s" format
        (if (fsdoc.title != null) fsdoc.title else fsdoc.getId))
      file.println(s"Article ID: ${fsdoc.getId}")
      for (sent <- doc.fsdoc; token <- sent.getTokens; form = token.getOrigForm;
           if form != "") {
        file.println(form)
      }
    }
  }

  def to_docgeo_ranker(driver: GeolocateDriver) =
    driver.create_ranker_from_document_streams(Iterable(("co-training",
        _ => get_doc_status_rawdocs.toIterator)))

  def debug_print(prefix: String = "") {
    for ((doc, index) <- docs.zipWithIndex) {
      doc.debug_print("%s#%s: " format (prefix, index + 1))
    }
  }
}

class CCorpusDocumentSource(corpus: CCorpus) extends DocumentSource {
  val iterator = corpus.docs.toSeq.map(_.fsdoc).iterator
  def hasNext = iterator.hasNext
  def next = iterator.next
}

// document geolocator
class DocGeo(val ranker: GridRanker[SphereCoord]) {
  def label_using_cell_evaluator(corpus: FieldSpringCCorpus) {
    val evaluator = ranker.grid.driver.asInstanceOf[GeolocateDriver].
      create_cell_evaluator(ranker)
    // FIXME: We need to call initialize() on the evaluator with all of the
    // GridDocs.
    for (doc <- corpus.docs) {
      doc.get_grid_doc(ranker) foreach { griddoc =>
        val maybe_result = evaluator.evaluate_document(griddoc)
        maybe_result match {
          case Left(errmess) =>
            errprint("Error evaluating document '%s': %s",
              griddoc.title, errmess)
          case Right(result) =>
            doc.fsdoc.setGoldCoord(result.pred_coord.lat,
              result.pred_coord.long)
            // FIXME: Set the score, which means we need to retrieve it one
            // way or other
        }
      }
    }
  }

  def label(corpus: FieldSpringCCorpus) {
    // FIXME: We need to call initialize() on the GridRanker with all of the
    // GridDocs.
    // FIXME: Can the GridRanker handle initialize() being called multiple
    // times? Fix it so it can.
    val task = new Meter("labeling", "document")
    corpus.docs.foreachMetered(task) { doc =>
      doc.get_grid_doc(ranker) foreach { griddoc =>
          // FIXME: This doesn't work with mean shift. Assumes we're always
          // taking the topmost cell.
        val cells_scores = 
          ranker.evaluate(griddoc, None, include_correct = false)
        val (cell, score) = cells_scores.head
        val coord = cell.get_centroid
        doc.fsdoc.setGoldCoord(coord.lat, coord.long)
        doc.score = score
      }
    }
  }

  def evaluate(corpus: FieldSpringCCorpus, prefix: String) {
    // BEWARE of sets, need to convert to sequences!
    val docs = corpus.docs.toIndexedSeq
    val errdists = docs.map(_.error_distance_from_nearest_predicted_toponym).
      filter(_ >= 0)
    errprint("%sNumber of documents with error distances: %s/%s",
      prefix, errdists.size, docs.size)
    errprint("%sAcc@161: %.2f", prefix,
      errdists.count(_ <= 161).toDouble / errdists.size)
    errprint("%sMean: %.2f km", prefix, mean(errdists))
    errprint("%sMedian: %.2f km", prefix, median(errdists))
  }

  def label_and_evaluate(corpus: FieldSpringCCorpus, prefix: String) {
    label(corpus)
    evaluate(corpus, prefix)
  }
}

//class InterpolatingDocGeo(r1: GridRanker[SphereCoord],
//    r2: GridRanker[SphereCoord], interp_factor: Double) extends DocGeo {
//
//  require(interp_factor >= 0 && interp_factor <= 1)
//
//  def label(corpus: FieldSpringCCorpus) {
//    // FIXME: We need to call initialize() on the GridRanker with all of the
//    // GridDocs.
//    // FIXME: Can the GridRanker handle initialize() being called multiple
//    // times? Fix it so it can.
//    for (doc <- corpus.docs) {
//      // FIXME: Does it matter that we supply r1 here instead of r2,
//      // or instead of creating the document twice, one for each ranker?
//      doc.get_grid_doc(r1) foreach { griddoc =>
//        // We match up the cells by going through the cells in one of the two,
//        // and for each cell's centroid, looking up the best cell in the other
//        // for this centroid.
//        val cells_scores_1 = 
//          r1.return_ranked_cells(griddoc, None, include_correct = false)
//        val cells_scores_2 = 
//          r2.return_ranked_cells(griddoc, None, include_correct = false)
//        val scores_2_map = cells_scores_2.toMap
//        val cells_scores =
//          cells_scores_1.flatMap { case (cell, score) =>
//            val cell2 = r2.grid.find_best_cell_for_coord(cell.get_centroid,
//              create_non_recorded = false)
//            cell2.flatMap { scores_2_map.get(_) } map { score2 =>
//              (cell, score * interp_factor + score2 * (1 - interp_factor))
//            }
//          }.toSeq.sortWith(_._2 > _._2)
//        if (cells_scores.size == 0)
//          errprint("Error evaluating document '%s': Interpolated cell list is empty",
//            griddoc.title)
//        else {
//          // FIXME: This doesn't work with mean shift. Assumes we're always
//          // taking the topmost cell.
//          val (cell, score) = cells_scores.head
//          val coord = cell.get_centroid
//          doc.fsdoc.setGoldCoord(coord.lat, coord.long)
//          doc.score = score
//        }
//      }
//    }
//  }
//}

object DocGeo {
  def train(corpus: CCorpus, driver: GeolocateDriver) =
    new DocGeo(corpus.to_docgeo_ranker(driver))
  /**
   * Interpolate between two document geolocators. We need to hook into
   * return_ranked_cells() in some way and match up the cells, interpolating
   * the scores. Matching up the cells can be done by going through the
   * cells in one of the two, and for each cell's centroid, looking up the
   * best cell in the other for this centroid. The question is whether we
   * should interpolate here or create a special GridRanker that
   * interpolates between two other GridRankers.
   */
  def interpolate(fg: DocGeo, bg: DocGeo, interp_factor: Double) =
    new DocGeo(new InterpolatingGridRanker(fg.ranker, bg.ranker,
      interp_factor))
}

class WistrSpiderResolver(logfile: String, wistr_dir: String,
    num_iterations: Int, document_coord: DOCUMENT_COORD) extends Resolver {

  var spider: Resolver = null

  override def train(corpus: StoredCorpus) {
    val weights_filename =
      File.createTempFile("textgrounder.wistr-spider.weights", null).toString
    val wistr = new ProbabilisticResolver(logfile, wistr_dir,
      weights_filename, 0.0, dgProbOnly = false, meProbOnly = true)
    spider = new WeightedMinDistResolver(num_iterations,
      weights_filename, logfile, document_coord)
    wistr.disambiguate(corpus)
  }

  def disambiguate(corpus: StoredCorpus): StoredCorpus = {
    if (spider == null)
      train(corpus)
    spider.disambiguate(corpus)
  }
}

class TopRes {
  def document_coord_to_enum(document_coord: String) = {
    document_coord match {
      case "no" => DOCUMENT_COORD.NO
      case "addtopo" => DOCUMENT_COORD.ADDTOPO
      case "weighted" => DOCUMENT_COORD.WEIGHTED
    }
  }

  def create_resolver(driver: GeolocateDriver, logfile: String,
      wistr_dir: String): Resolver = {
    val params = driver.params
    params.topres_resolver match {
      case "random" => new RandomResolver
      case "population" => new PopulationResolver
      case "spider" => new WeightedMinDistResolver(params.topres_iterations,
        params.topres_weights_file, logfile,
        document_coord_to_enum(params.topres_spider_document_coord))
      case "maxent" => new MaxentResolver(logfile, wistr_dir)
      case "wistr-spider" => new WistrSpiderResolver(logfile, wistr_dir,
        params.topres_iterations,
        document_coord_to_enum(params.topres_spider_document_coord))
      case "prob" => new ProbabilisticResolver(logfile,
        wistr_dir, params.topres_write_weights_file,
        params.topres_pop_component, params.topres_dg,
        params.topres_me)
    }
  }

  // Given a corpus labeled with both document location and toponyms,
  // and another corpus labeled only with document locations, effectively
  // train WISTR on the combination of the two and a version of Wikipedia.
  // We actually require that the Wikipedia feature generation be previously
  // done, since it's time consuming and we can reuse it each time.
  def train_wistr(labeled: FieldSpringCCorpus, dglabeled: FieldSpringCCorpus,
      driver: GeolocateDriver) = {

    require(driver.params.topres_input != null,
      "--topres-input must be specified for WISTR training")
    require(driver.params.topres_gazetteer != null,
      "--topres-gazetteer must be specified for WISTR training")
    require(driver.params.topres_stopwords != null,
      "--topres-stopwords must be specified for WISTR training")
    require(driver.params.topres_wistr_feature_dir != null,
      "--topres-wistr-feature-dir must be specified for WISTR training")

    if (debug("cotrain")) {
      errprint("Labeled corpus:")
      labeled.debug_print()
      errprint("DG-labeled corpus:")
      dglabeled.debug_print()
    }
 
    val fh = localfh

    val labeled_rows = labeled.get_rawdocs.map(_.row)
    val dglabeled_rows = dglabeled.get_rawdocs.map(_.row)
    val rows_filename =
      File.createTempFile("textgrounder.cotrain.rows", null).toString
    errprint("Writing rows to %s.data.txt", rows_filename)
    TextDB.write_textdb_rows(fh, rows_filename,
      (labeled_rows ++ dglabeled_rows).toIterator)
    val text_filename =
      File.createTempFile("textgrounder.cotrain.text", null).toString
    errprint("Writing text to %s", text_filename)
    val textfile = fh.openw(text_filename)
    labeled.write_corpus_text(textfile)
    dglabeled.write_corpus_text(textfile)
    textfile.close()

    val wistr_dir =
      Files.createTempDirectory("textgrounder.cotrain.wistr.dir").toString
    errprint("Using WISTR dir %s", wistr_dir)
    val args = Array("-w", text_filename, "-c", s"$rows_filename.data.txt",
      "-i", driver.params.topres_input, "-g", driver.params.topres_gazetteer,
      "-s", driver.params.topres_stopwords, "-d", wistr_dir)
    errprint("Executing: SupervisedTRFeatureExtractor %s", args mkString " ")
    SupervisedTRFeatureExtractor.main(args)

    // Combine the txt files in WISTR_DIR with the txt files in
    // --topres-wistr-feature-dir.
    //
    errprint("Combining WISTR Wikipedia feature files in %s with newly generated feature files in %s",
      driver.params.topres_wistr_feature_dir, wistr_dir)
    for (path <- fh.list_files(driver.params.topres_wistr_feature_dir);
         if path.endsWith(".txt")) {
      val (dir, tail) = fh.split_filename(path)
      val wistr_dir_file = fh.join_filename(wistr_dir, tail)
      val outfile = fh.openw(wistr_dir_file, append = fh.exists(wistr_dir_file))
      for (line <- fh.openr(path))
        outfile.println(line)
      outfile.close()
    }

    // Train the WISTR models using OpenNLP.
    val args2 = Array(wistr_dir)
    errprint("Executing: SupervisedTRMaxentModelTrainer %s", args2 mkString " ")
    SupervisedTRMaxentModelTrainer.main(args2)

    wistr_dir
  }

  // Do any necessary training given a corpus labeled with both document
  // location and toponyms, and another corpus labeled only with document
  // locations; the two are meant to be combined. Currently this does
  // WISTR training; SPIDER doesn't require training. Return a resolver
  // object to be passed into the resolve() function.
  def train(labeled: FieldSpringCCorpus, dglabeled: FieldSpringCCorpus,
      driver: GeolocateDriver) = {
    val wistr_dir =
      if (driver.params.topres_resolver == "maxent" ||
          // In case we rename maxent -> wistr
          driver.params.topres_resolver == "wistr" ||
          driver.params.topres_resolver == "wistr-spider")
        train_wistr(labeled, dglabeled, driver)
      else
        null
    create_resolver(driver, driver.params.topres_log_file, wistr_dir)
  }

  def resolve(corpus: FieldSpringCCorpus, resolver: Resolver) = {
    val stored_corpus = corpus.to_stored_corpus
    resolver.disambiguate(stored_corpus)
  }
}

class CoTrainer {
  def choose_batch(corpus: FieldSpringCCorpus,
      threshold: Double, minsize: Int): FieldSpringCCorpus = {
    val meet_threshold = corpus.docs.filter { _.score >= threshold }
    if (meet_threshold.size >= minsize)
      new FieldSpringCCorpus(meet_threshold)
    else {
      val sorted = corpus.docs.toSeq.sortWith(_.score > _.score)
      // FIXME: Do we want to preserve the order of the documents in `corpus`?
      // If so we need to find the minimum allowed score and filter the
      // docs that way.
      new FieldSpringCCorpus(sorted.take(minsize))
    }
  }

  /**
   * Construct a pseudo-document created from a given toponym and the tokens
   * surrounding that toponym. The location of the document is set to the
   * location of the toponym.
   */
  def make_pseudo_document(id: String, toponym: Toponym, tokens: Iterable[Token]
    ): CDoc = {
    val coord = toponym.getSelected.getRegion.getCenter
    val lat = coord.getLatDegrees
    val long = coord.getLngDegrees
    val doc = new GeoTextDocument(id, "unknown", lat, long)
    doc.addSentence(new SimpleSentence("1", tokens.toSeq))
    new CDoc(doc, 0.0)
  }

  var next_id = 0

  /**
   * For each toponym in each document, construct a pseudo-document consisting
   * of the words within `window` words of the toponym, with the location of
   * the pseudo-document set to the toponym's location.
   */
  def get_pseudo_documents_surrounding_toponyms(corpus: FieldSpringCCorpus,
      window: Int, stoplist: Set[String]) = {
    for (doc <- corpus.docs;
         doc_as_array = TextUtil.getDocAsArray(doc.fsdoc);
         (token, tok_index) <- doc_as_array.zipWithIndex;
         if token.isToponym;
         toponym = token.asInstanceOf[Toponym]
         if toponym.getAmbiguity > 0 && toponym.hasSelected) yield {
      next_id += 1
      val start_index = math.max(0, tok_index - window)
      val end_index = math.min(doc_as_array.size, tok_index + window + 1)
      val doc_tokens = doc_as_array.slice(start_index, end_index).
        filterNot(tok => stoplist(tok.getForm))
      make_pseudo_document("%s-%s" format (doc.fsdoc.getId, next_id),
        toponym, doc_tokens)
    }
  }

  /**
   * Do co-training, given a base docgeo-labeled corpus (e.g. Wikipedia) and
   * an unlabeled corpus containing toponym candidate annotations.
   *
   * Co-training involves alternating between two classifiers, training
   * each one on the predictions of the other.
   */
  def train(base: GridRanker[SphereCoord], unlabeled: FieldSpringCCorpus
      ): (DocGeo, Resolver, FieldSpringCCorpus) = {
    val driver = base.grid.driver.asInstanceOf[GeolocateDriver]
    // Set of labeled documents, originally empty. These have been labeled
    // by the document geolocator and passed to the toponym resolver,
    // which then resolves toponyms in them. As a result they have both
    // document and toponym labels (the former as the gold labels, the
    // latter as the "selected" labels rather than the gold ones).
    var labeled = new FieldSpringCCorpus()
    // Set of labeled pseudo-documents corresponding to labeled documents.
    // After the labeled documents are toponym-resolved, for every toponym
    // a pseudo-document is created by taking a window of 10 or so words
    // on each side of the toponym, with the toponym's location as the
    // document's location. These also have both document and toponym
    // labels.
    var labeled_pseudo = new FieldSpringCCorpus()
    // toponym resolver, possibly trained on wp (if WISTR)
    var topres = new TopRes
    val wp_docgeo = new DocGeo(base)
    var docgeo: DocGeo = null
    var resolver: Resolver = null
    var dist_threshold = driver.params.co_train_min_distance
    var outer_iteration = 0
    breakable {
      while (true) {
        var iteration = 0
        outer_iteration += 1
        breakable {
          while (true) {
            iteration += 1
            val iter_string = "%s-%s" format (outer_iteration, iteration)
            errprint("Iteration %s: Size of unlabeled corpus: %s docs",
              iter_string, unlabeled.size)
            errprint("Iteration %s: Size of labeled corpus: %s docs",
              iter_string, labeled.size)
            errprint("Iteration %s: Size of labeled-pseudo corpus: %s docs",
              iter_string, labeled_pseudo.size)
            // Create a new document geolocator based on the labeled pseudo-docs,
            // and then create a second document geolocator that interpolates
            // between the first one and the base docgeo (e.g. trained on
            // Wikipedia).
            val docgeo1 = DocGeo.train(labeled_pseudo, driver)
            docgeo = DocGeo.interpolate(docgeo1, wp_docgeo,
              driver.params.co_train_interpolate_factor)
            // Label remaining unlabeled docs with docgeo.
            docgeo.label(unlabeled)
            // Choose some batch, based on the score.
            val chosen_dglabeled = choose_batch(unlabeled,
              driver.params.co_train_min_score, driver.params.co_train_min_size)
            errprint("Iteration %s: Size of chosen unlabeled: %s docs",
              iter_string, chosen_dglabeled.size)
            // Toponym resolver further winnows the batch, only accepting
            // documents with some candidate near the chosen docgeo location.
            val accepted_dglabeled =
              chosen_dglabeled.filter(doc => doc.toponym_candidate_near_location(
                dist_threshold))
            errprint("Iteration %s: Size of accepted labeled: %s docs",
              iter_string, accepted_dglabeled.size)
            if (accepted_dglabeled.is_empty) {
              errprint("Terminating, accepted-labeled corpus is empty")
              break
            }
            if (debug("cotrain")) {
              errprint("Accepted-DG-labeled corpus:")
              accepted_dglabeled.debug_print()
            }
            // At this point, if we need to train the toponym resolver, e.g.
            // WISTR, we train it on the combination of the `labeled` and
            // `accepted_dglabeled` docs. Note that the former has both docgeo
            // and toponym labels, while the latter has only docgeo labels.
            // For WISTR, this also depends on the whole Wikipedia corpus.
            // It should be possible to do feature extraction on Wikipedia only
            // once and then combine the features.
            resolver = topres.train(labeled, accepted_dglabeled, driver)

            // Do toponym resolution on the accepted batch of documents labeled
            // by the docgeo.
            val resolved_stored_corpus =
              topres.resolve(accepted_dglabeled, resolver)
            if (debug("cotrain")) {
              errprint("Resolved stored corpus:")
              FieldSpringCCorpus.debug_print_corpus(resolved_stored_corpus)
            }
            val resolved = FieldSpringCCorpus(resolved_stored_corpus)
            if (debug("cotrain")) {
              errprint("Resolved corpus:")
              resolved.debug_print()
            }
            // Create pseudo-documents based on the accepted, toponym-resolved
            // batch of documents.
            val resolved_pseudo =
              get_pseudo_documents_surrounding_toponyms(resolved,
                driver.params.co_train_window, driver.the_stopwords)
            if (debug("cotrain")) {
              errprint("Iteration %s: Size of resolved-pseudo corpus: %s docs",
                iter_string, resolved_pseudo.size)
              errprint("Resolved-pseudo corpus:")
              for ((doc, index) <- resolved_pseudo.zipWithIndex)
                doc.debug_print("#%s: " format (index + 1))
            }
            // Add pseudo-documents to current set to be passed to the docgeo.
            for (doc <- resolved_pseudo)
              labeled_pseudo += doc
            // Remove accepted labeled batch from set of unlabeled docs, and
            // add corresponding set with toponym labels to set of labeled docs.
            for (doc <- accepted_dglabeled.docs) {
              unlabeled -= doc
            }
            for (doc <- resolved.docs) {
              labeled += doc
            }
            if (unlabeled.is_empty) {
              errprint("Terminating, unlabeled corpus is empty")
              break
            }
          }
        }
        // We increase the distance threshold each time. If we haven't
        // reached the maximum, but increasing it puts it over, we set
        // it to the maximum and do the last iteration.
        if (dist_threshold == driver.params.co_train_max_distance)
          break
        dist_threshold *= driver.params.co_train_distance_factor
        if (dist_threshold > driver.params.co_train_max_distance)
          dist_threshold = driver.params.co_train_max_distance
      }
    }

    (docgeo, resolver, labeled)
  }

  def corpus_format_to_enum(corpus_format: String) = {
    corpus_format match {
      case "trconll" => CORPUS_FORMAT.TRCONLL
      case "raw" => CORPUS_FORMAT.PLAIN
      case "geotext" => CORPUS_FORMAT.GEOTEXT
      case "wikitext" => CORPUS_FORMAT.WIKITEXT
    }
  }

  def read_fieldspring_test_corpus(corpus: String) = {
    errout(s"Reading serialized corpus from $corpus ...")
    val test_corpus = TopoUtil.readStoredCorpusFromSerialized(corpus)
    errprint("done.")
    test_corpus
  }

  def read_fieldspring_gold_corpus(corpus: String, corpus_format: String) = {
    val tokenizer = new OpenNLPTokenizer()
    // val recognizer = new OpenNLPRecognizer()

    val gold_corpus = Corpus.createStoredCorpus
    corpus_format match {
      case "trconll" => {
        errout(s"Reading gold corpus from $corpus ...")
        val gold_file = new File(corpus)
        if(gold_file.isDirectory)
            gold_corpus.addSource(new TrXMLDirSource(gold_file, tokenizer))
        else
            gold_corpus.addSource(new TrXMLSource(new BufferedReader(new FileReader(gold_file)), tokenizer))
        gold_corpus.setFormat(CORPUS_FORMAT.TRCONLL)
        gold_corpus.load()
        errprint("done.")
      }
    }
    gold_corpus
  }

  def evaluate_topres_corpus(test_corpus: StoredCorpus,
      gold_corpus: StoredCorpus, prefix: String, corpus_format: String,
      do_oracle_eval: Boolean) = {
    if (debug("cotrain")) {
      errprint("Test corpus:")
      FieldSpringCCorpus.debug_print_corpus(test_corpus)
      errprint("Gold corpus:")
      FieldSpringCCorpus.debug_print_corpus(gold_corpus)
    }
    val evaluate_corpus = new EvaluateCorpus
    evaluate_corpus.doEval(test_corpus, gold_corpus, prefix,
      corpus_format_to_enum(corpus_format), true, do_oracle_eval)
  }
}

