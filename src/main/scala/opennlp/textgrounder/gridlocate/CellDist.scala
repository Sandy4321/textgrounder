///////////////////////////////////////////////////////////////////////////////
//  CellDist.scala
//
//  Copyright (C) 2010-2013 Ben Wing, The University of Texas at Austin
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
package gridlocate

import collection.mutable

import util.collection.{LRUCache, doublemap}
import util.print.{errprint, warning}

import langmodel.{Gram,LangModel}

/**
 * A general distribution over cells, associating a probability with each
 * cell.  The caller needs to provide the probabilities.
 */

class CellDist[Co](
  val grid: Grid[Co]
) {
  val cellprobs = mutable.Map[GridCell[Co], Double]()

  def set_cell_probabilities(
      probs: collection.Map[GridCell[Co], Double]) {
    cellprobs.clear()
    cellprobs ++= probs
  }

  def get_ranked_cells(include: Iterable[GridCell[Co]]) = {
    val probs =
      if (include.size == 0)
        cellprobs
      else
        // Elements on right override those on left
        include.map((_, 0.0)).toMap ++ cellprobs.toMap
    // sort by second element of tuple, in reverse order
    probs.toIndexedSeq sortWith (_._2 > _._2)
  }
}

/**
 * Distribution over cells that is associated with a gram. This class knows
 * how to populate its own probabilities, based on the relative probabilities
 * of the gram in the language models of the various cells.  That is,
 * if we have a set of cells, each with a language model, then we can
 * imagine conceptually inverting the process to generate a cell distribution
 * over grams.  Basically, for a given gram, look to see what its probability
 * is in all cells; normalize, and we have a cell distribution.
 *
 * Instances of this class are normally generated by a factory, specifically
 * `CellDistFactory` or a subclass.  Currently only used by `SphereGramCellDist`
 * and `SphereCellDistFactory`; see them for info on how they are used.
 *
 * @param gram Gram for which the cell is computed
 * @param cellprobs Hash table listing probabilities associated with cells
 */

class GramCellDist[Co](
  grid: Grid[Co],
  val gram: Gram
) extends CellDist[Co](grid) {
  var normalized = false

  protected def init() {
    // It's expensive to compute the value for a given gram so we cache
    // language models.
    var totalprob = 0.0
    // Compute and store un-normalized probabilities for all cells
    for (cell <- grid.iter_nonempty_cells) {
      val prob = cell.grid_lm.gram_prob(gram)
      // Another way of handling zero probabilities.
      /// Zero probabilities are just a bad idea.  They lead to all sorts of
      /// pathologies when trying to do things like "normalize".
      //if (prob == 0.0)
      //  prob = 1e-50
      cellprobs(cell) = prob
      totalprob += prob
    }
    // Normalize the probabilities; but if all probabilities are 0, then
    // we can't normalize, so leave as-is. (FIXME When can this happen?
    // It does happen when you use --mode=generate-kml and specify grams
    // that aren't seen.  In other circumstances, the smoothing ought to
    // ensure that 0 probabilities don't exist?  Anything else I missed?)
    if (totalprob != 0) {
      normalized = true
      for ((cell, prob) <- cellprobs)
        cellprobs(cell) /= totalprob
    } else
      normalized = false
  }

  init()
}

/**
 * Factory object for creating CellDists, i.e. objects describing a
 * distribution over cells.  You can create two types of CellDists, one for
 * a single gram and one based on a distribution of grams (language model).
 * The former process returns a GramCellDist, which initializes the probability
 * distribution over cells as described for that class.  The latter process
 * returns a basic CellDist.  It works by retrieving GramCellDists for
 * each of the grams in the distribution, and then averaging all of these
 * distributions, weighted according to probability of the gram in the
 * language model.
 *
 * The call to `get_cell_dist` on this class either locates a cached
 * distribution or creates a new one, using `create_gram_cell_dist`,
 * which creates the actual `GramCellDist` class.
 *
 * @param lru_cache_size Size of the cache used to avoid creating a new
 *   GramCellDist for a given gram when one is already available for that
 *   gram.
 */

class CellDistFactory[Co](
  val lru_cache_size: Int
) {
  def create_gram_cell_dist(
    grid: Grid[Co], gram: Gram
  ) = new GramCellDist[Co](grid, gram)

  var cached_dists: LRUCache[Gram, GramCellDist[Co]] = null

  /**
   * Return a cell distribution over a single gram, using a least-recently-used
   * cache to optimize access.
   */
  def get_cell_dist(grid: Grid[Co], gram: Gram) = {
    if (cached_dists == null)
      cached_dists = new LRUCache(maxsize = lru_cache_size)
    cached_dists.get(gram) match {
      case Some(dist) => dist
      case None => {
        val dist = create_gram_cell_dist(grid, gram)
        cached_dists(gram) = dist
        dist
      }
    }
  }

  /**
   * Return a cell distribution over a language model.  This works
   * by adding up the unsmoothed language models of the individual grams,
   * weighting by the count of the each gram.
   */
  def get_cell_dist_for_lang_model(grid: Grid[Co], lang_model: LangModel) = {
    val cellprobs = doublemap[GridCell[Co]]()
    for ((gram, count) <- lang_model.iter_grams) {
      val dist = get_cell_dist(grid, gram)
      for ((cell, prob) <- dist.cellprobs)
        cellprobs(cell) += count * prob
    }
    val totalprob = (cellprobs.values sum)
    for ((cell, prob) <- cellprobs)
      cellprobs(cell) /= totalprob
    val retval = new CellDist[Co](grid)
    retval.set_cell_probabilities(cellprobs)
    retval
  }
}

