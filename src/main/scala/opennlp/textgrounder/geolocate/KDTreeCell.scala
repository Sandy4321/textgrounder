///////////////////////////////////////////////////////////////////////////////
//  KDTreeGrid.scala
//
//  Copyright (C) 2011, 2012 Stephen Roller, The University of Texas at Austin
//  Copyright (C) 2011-2013 Ben Wing, The University of Texas at Austin
//  Copyright (C) 2011 Mike Speriosu, The University of Texas at Austin
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
import scala.collection.mutable.Map

import ags.utils.KdTree

import util.spherical.SphereCoord
import util.experiment._
import util.print.{errprint, warning}
import util.textdb.Row

import langmodel.UnigramLangModel

import gridlocate.DocStatus

class KdTreeCell(
  cellgrid: KdTreeGrid,
  val kdleaf : KdTree
) extends RectangularCell(cellgrid) {

  def get_northeast_coord =
    new SphereCoord(kdleaf.minLimit(0), kdleaf.minLimit(1))

  def get_southwest_coord =
    new SphereCoord(kdleaf.maxLimit(0), kdleaf.maxLimit(1))

  def describe_indices = "Placeholder"
}

object KdTreeGrid {
  def apply(docfact: SphereDocFactory, bucketSize: Int,
            splitMethod: String, useBackoff: Boolean,
            interpolateWeight: Double = 0.0) : KdTreeGrid = {
    new KdTreeGrid(docfact, bucketSize, splitMethod match {
      case "halfway" => KdTree.SplitMethod.HALFWAY
      case "median" => KdTree.SplitMethod.MEDIAN
      case "maxmargin" => KdTree.SplitMethod.MAX_MARGIN
    }, useBackoff, interpolateWeight)
  }
}

class KdTreeGrid(
  docfact: SphereDocFactory,
  bucketSize: Int,
  splitMethod: KdTree.SplitMethod,
  useBackoff: Boolean,
  interpolateWeight: Double
) extends SphereGrid(docfact) {
  /**
   * Total number of cells in the grid.
   */
  var total_num_cells: Int = 0
  var kdtree: KdTree = new KdTree(2, bucketSize, splitMethod)

  val nodes_to_cell: Map[KdTree, KdTreeCell] = Map()
  val leaves_to_cell: Map[KdTree, KdTreeCell] = Map()

  def add_training_documents_to_grid(
      get_rawdocs: String => Iterator[DocStatus[Row]]) {
    for (doc <- docfact.raw_documents_to_documents(
           get_rawdocs("preliminary pass to generate K-d tree: reading"),
           note_globally = false,
           finish_globally = false)) {
      kdtree.addPoint(Array(doc.coord.lat, doc.coord.long))
    }

    // we've seen all the coordinates. we need to build up
    // the entire kd-tree structure now, the centroids, and
    // clean out the data.

    val task =
      driver.show_progress("generating", "K-d tree structure").start()

    // build the full kd-tree structure.
    kdtree.balance

    for (node <- kdtree.getNodes) {
      val c = new KdTreeCell(this, node)
      nodes_to_cell.update(node, c)
      task.item_processed()
    }
    task.finish()

    // no longer need to keep all our locations in memory. destroy
    // them. to free up memory.
    kdtree.annihilateData

    // Now read normally.
    default_add_training_documents_to_grid(get_rawdocs, doc => {
      val leaf = kdtree.getLeaf(Array(doc.coord.lat, doc.coord.long))
      var n = leaf
      while (n != null) {
        nodes_to_cell(n).add_document(doc)
        n = n.parent
      }
    })
  }

  def find_best_cell_for_coord(coord: SphereCoord,
      create_non_recorded: Boolean) = {
    // FIXME: implementation note: the KD tree should tile the entire
    // earth's surface, but there's a possibility of something going awry
    // here if we've never seen an evaluation point before.
    Option(leaves_to_cell(kdtree.getLeaf(Array(coord.lat, coord.long))))
  }

  /**
   * Generate all non-empty cells.  This will be called once (and only once),
   * after all documents have been added to the cell grid by calling
   * `add_document_to_cell`.  The generation happens internally; but after
   * this, `iter_nonempty_cells` should work properly.
   */
  def initialize_cells() {
    total_num_cells = kdtree.getLeaves.size
    num_non_empty_cells = total_num_cells

    // need to finish generating all the language models
    for (c <- nodes_to_cell.valuesIterator) {
      c.finish()
    }

    if (interpolateWeight > 0) {
      // this is really gross. we need to interpolate all the nodes
      // by modifying their langmodel.count map. We are breaking
      // so many levels of abstraction by doing this AND preventing
      // us from using interpolation with bigrams :(
      //

      // We'll do it top-down so dependencies are met.

      val iwtopdown = true
      val nodes =
        if (iwtopdown) kdtree.getNodes.toList
        else kdtree.getNodes.reverse

      driver.show_progress("interpolating", "K-d tree cell").
      foreach(nodes.filter(_.parent != null)) { node =>
        val cell = nodes_to_cell(node)
        val wd = cell.lang_model
        val model = wd.asInstanceOf[UnigramLangModel].model

        for ((k,v) <- model.iter_items) {
          model.set_item(k, (1 - interpolateWeight) * v)
        }

        val pcell = nodes_to_cell(node.parent)
        val pwd = pcell.lang_model
        val pmodel = pwd.asInstanceOf[UnigramLangModel].model

        for ((k,v) <- pmodel.iter_items) {
          val oldv = if (model contains k) model.get_item(k) else 0.0
          val newv = oldv + interpolateWeight * v
          if (newv > interpolateWeight)
            model.set_item(k, newv)
        }
      }
    }

    // here we need to drop nonleaf nodes unless backoff is enabled.
    val nodes = if (useBackoff) kdtree.getNodes else kdtree.getLeaves
    for (node <- nodes) {
      leaves_to_cell.update(node, nodes_to_cell(node))
    }
  }

  /**
   * Iterate over all non-empty cells.
   */
  def iter_nonempty_cells: Iterable[SphereCell] = {
    val nodes = if (useBackoff) kdtree.getNodes else kdtree.getLeaves
    for (leaf <- nodes if leaf.size > 0)
        yield leaves_to_cell(leaf)
  }
}

