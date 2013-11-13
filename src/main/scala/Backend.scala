/*
 Copyright (c) 2011, 2012, 2013 The Regents of the University of
 California (Regents). All Rights Reserved.  Redistribution and use in
 source and binary forms, with or without modification, are permitted
 provided that the following conditions are met:

    * Redistributions of source code must retain the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      two paragraphs of disclaimer in the documentation and/or other materials
      provided with the distribution.
    * Neither the name of the Regents nor the names of its contributors
      may be used to endorse or promote products derived from this
      software without specific prior written permission.

 IN NO EVENT SHALL REGENTS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF
 REGENTS HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 REGENTS SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT
 LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 A PARTICULAR PURPOSE. THE SOFTWARE AND ACCOMPANYING DOCUMENTATION, IF
 ANY, PROVIDED HEREUNDER IS PROVIDED "AS IS". REGENTS HAS NO OBLIGATION
 TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 MODIFICATIONS.
*/

package Chisel

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.{Queue=>ScalaQueue}
import scala.collection.mutable.Stack
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap
import java.lang.reflect.Modifier._
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream

import Reg._
import ChiselError._

object Backend {
  var moduleNamePrefix = ""
}

abstract class Backend {

  /* Set of keywords which cannot be used as node and component names. */
  val keywords: HashSet[String];

  var traversalIndex = 0

  def createOutputFile(name: String): java.io.FileWriter = {
    val baseDir = ensureDir(Module.targetDir)
    new java.io.FileWriter(baseDir + name)
  }

  def depthString(depth: Int): String = {
    var res = "";
    for (i <- 0 until depth)
      res += "  ";
    res
  }

  var nindex = 0
  def emitIndex : Int = { val res = nindex; nindex = nindex + 1; res }


  /** Ensures a directory *dir* exists on the filesystem. */
  def ensureDir(dir: String): String = {
    val d = dir + (if (dir == "" || dir(dir.length-1) == '/') "" else "/")
    new File(d).mkdirs()
    d
  }

  def extractClassName(comp: Module): String = {
    val cname  = comp.getClass().getName().replace("$", "_")
    val dotPos = cname.lastIndexOf('.');
    Backend.moduleNamePrefix + (
      if (dotPos >= 0) cname.substring(dotPos + 1) else cname);
  }

  protected def genIndent(x: Int): String = {
    if(x == 0) "" else "    " + genIndent(x-1);
  }

  def nameChildren(root: Module) {
    // Name all nodes at this level
    root.io.nameIt("io");
    val nameSpace = new HashSet[String];
    /* We are going through all declarations, which can return Datas,
     ArrayBuffer[Data], BlackBox and Modules.
     Since we call invoke() to get a proper instance of the correct type,
     we have to insure the method is accessible, thus all fields
     that will generate C++ or Verilog code must be made public. */
    for (m <- root.getClass().getDeclaredMethods) {
      val name = m.getName();
      val types = m.getParameterTypes();
      if (types.length == 0
        && isPublic(m.getModifiers()) && !(Module.keywords contains name)) {
        val o = m.invoke(root);
        o match {
         case node: Data => {
           /* XXX It seems to always be true. How can name be empty? */
           if ((name != ""
             || node.name == null || (node.name == "" && !node.named))) {
             node.nameIt(asValidName(name));
           }
           nameSpace += node.name;
         }
         case buf: ArrayBuffer[_] => {
           /* We would prefer to match for ArrayBuffer[Data] but that's
            impossible because of JVM constraints which lead to type erasure.
            XXX Using Seq instead of ArrayBuffer will pick up members defined
            in Module that are solely there for implementation purposes. */
           if(!buf.isEmpty && buf.head.isInstanceOf[Data]){
             val nodebuf = buf.asInstanceOf[Seq[Data]];
             var i = 0;
             for(elm <- nodebuf){
               if( elm.name == null || elm.name.isEmpty ) {
                 /* XXX This code is sensitive to when Bundle.nameIt is called.
                  Whether it is called late (elm.name is empty) or we override
                  any previous name that could have been infered,
                  this has for side-effect to create modules with the exact
                  same logic but textually different in input/output
                  parameters, hence generating unnecessary modules. */
                 elm.nameIt(asValidName(name + "_" + i));
               }
               nameSpace += elm.name;
               i += 1;
             }
           }
         }
         case bb: BlackBox => {
           if(!bb.named) {
             bb.name = name;
             bb.named = true
           };
           nameSpace += bb.name;
         }
         case comp: Module => {
           if(!comp.named) {
             comp.name = asValidName(name);
             comp.named = true
           };
           nameSpace += comp.name;
         }
         case any => {
           /* We have no idea what to do with class members which are
            neither of the previous types. Let's discard them. */
         }
        }
      }
    }
    /* Recursively name the nodes and components inside this root.
     This code must be executed between the root-level naming and the naming
     of bindings otherwise some identifiers will leak into the input/output
     of a module. */
    val byNames = new HashMap[String, ArrayBuffer[Module] ];
    for (c <- root.children) {
      nameChildren(c);
      if( c.name.isEmpty ) {
        /* We don't have a name because we are not dealing with
         a class member. */
        val className = extractClassName(c);
        if( byNames contains className ) {
          byNames(className).append(c);
        } else {
          byNames += (className -> ArrayBuffer[Module](c));
        }
      }
    }

    for( (className, comps) <- byNames ) {
        if( comps.length > 1 ) {
          for( (c, index) <- comps.zipWithIndex ) {
            c.name = className + "_" + index.toString
          }
        } else {
          comps(0).name = className;
        }
    }

    for (bind <- root.bindings) {
      var genName = if (bind.target.name == null || bind.target.name.length() == 0) "" else bind.target.component.name + "_" + bind.target.name;
      if(nameSpace.contains(genName)) genName += ("_" + emitIndex);
      bind.name = asValidName(genName); // Not using nameIt to avoid override
      bind.named = true;
    }
  }

  /* Returns a string derived from _name_ that can be used as a valid
   identifier for the targeted backend. */
  def asValidName( name: String ): String = {
    if (keywords.contains(name)) name + "_" else name;
  }

  def nameAll(root: Module) {
    nindex = 0
    root.name = extractClassName(root);
    nameChildren(root);
  }

  def fullyQualifiedName( m: Node ): String = {
    m match {
      case l: Literal => l.toString;
      case any       =>
        if (m.name != ""
          && m != Module.topComponent.defaultResetPin && m.component != null) {
          /* Only modify name if it is not the reset signal
           or not in top component */
          if(m.name != "reset" && m.component != Module.topComponent) {
            m.component.getPathName + "__" + m.name;
          } else {
            m.name
          }
        } else {
          m.name
        }
    }
  }

 def emitTmp(node: Node): String =
    emitRef(node)

  def emitRef(node: Node): String = {
    node match {
      case r: RegDelay =>
        if (r.name == "") r.name = "R" + emitIndex
        r.name
      case _ =>
        if(node.name == "") node.name = "T" + emitIndex
        node.name
    }
  }

  def emitRef(c: Module): String =
    c.name

  def emitDec(node: Node): String = ""

  val preElaborateTransforms = ArrayBuffer[(Module) => Unit]()
  val transforms = ArrayBuffer[(Module) => Unit]()

  def initializeDFS: Stack[Node] = {
    val res = new Stack[Node]

    /* XXX Make sure roots are consistent between initializeBFS, initializeDFS
     and findRoots.
     */
    for( c <- Module.components ) {
      for( a <- c.debugs ) {
        res.push(a)
      }
      for((n, flat) <- c.io.flatten) {
        res.push(flat.node)
      }
    }
    res
  }

  /** Nodes which are created outside the execution trace from the toplevel
    component constructor (i.e. through the () => Module(new Top()) ChiselMain
    argument) will have a component field set to null. For example, genMuxes,
    forceMatchWidths and transforms (all called from Backend.elaborate) create
    such nodes.

    This method walks all nodes from all component roots (outputs, debugs).
    and reassociates the component to the node both ways (i.e. in Module.nodes
    and Node.component).

    We assume here that all nodes at the components boundaries (io) have
    a non-null and correct node/component association. We further assume
    that nodes generated in elaborate are inputs to a node whose component
    field is set.

    Implementation Node:
    At first we did implement *collectNodesIntoComp* to handle a single
    component at a time but that did not catch the cases where Regs are
    passed as input to sub-module without being tied to an output
    of *this.component*.
    */
  def collectNodesIntoComp(dfsStack: Stack[Node]) {
    val walked = new HashSet[Node]()
    walked ++= dfsStack
    // invariant is everything in the stack is walked and has a non-null component
    while(!dfsStack.isEmpty) {
      val node = dfsStack.pop
      /*
      we're tracing from outputs -> inputs, so if node is an input, then its
      inputs belong to the outside component. Otherwise, its inputs are the same
      as node's inputs.
      */
      val curComp = node.component
      if (!node.component.nodes.contains(node))
        node.component.nodes += node
      for (input <- node.inputs) {
        if( input != null ) {
          if(!walked.contains(input)) {
            if( input.component == null ) {
              input.component = curComp
            }
            walked += input
            dfsStack.push(input)
          }
        }
      }
    }

    assert(dfsStack.isEmpty)
  }

  def transform(c: Module, transforms: ArrayBuffer[(Module) => Unit]): Unit = {
    for (t <- transforms)
      t(c)
  }

  def pruneUnconnectedIOs(m: Module) {
    val inputs = m.io.flatten.filter(x => x._2.node.isInstanceOf[IOBound] && x._2.node.asInstanceOf[IOBound].dir == INPUT)
    val outputs = m.io.flatten.filter(x => x._2.node.isInstanceOf[IOBound] && x._2.node.asInstanceOf[IOBound].dir == OUTPUT)

    for ((name, i) <- inputs) {
      val node = i.node
      if (node.inputs.length == 0 && m != Module.topComponent)
        if (node.consumers.length > 0) {
          if (Module.warnInputs)
            ChiselError.warning({"UNCONNECTED INPUT " + emitRef(node) + " in COMPONENT " + node.component +
                                 " has consumers"})
          node.driveRand = true
        } else {
          if (Module.warnInputs)
            ChiselError.warning({"FLOATING INPUT " + emitRef(node) + " in COMPONENT " + node.component})
          node.prune = true
        }
    }

    for ((name, o) <- outputs) {
      val node = o.node
      if (node.inputs.length == 0) {
        if (node.consumers.length > 0) {
          if (Module.warnOutputs)
            /* XXX fix line
            ChiselError.warning({"UNCONNETED OUTPUT " + emitRef(node) + " in component " + node.component + " has consumers on line " + node.consumers(0).line})
             */
          node.driveRand = true
        } else {
          if (Module.warnOutputs)
            ChiselError.warning({"FLOATING OUTPUT " + emitRef(node) + " in component " + node.component})
          node.prune = true
        }
      }
    }
  }

  def pruneNodes {
    val walked = new HashSet[Node]
    val bfsQueue = new ScalaQueue[Node]
    for (node <- Module.randInitIOs) bfsQueue.enqueue(node)
    var pruneCount = 0

    // conduct bfs to find all reachable nodes
    while(!bfsQueue.isEmpty){
      val top = bfsQueue.dequeue
      walked += top
      val prune = top.inputs.map(_.prune).foldLeft(true)(_ && _)
      pruneCount+= (if (prune) 1 else 0)
      top.prune = prune
      for(i <- top.consumers) {
        if(!(i == null)) {
          if(!walked.contains(i)) {
            bfsQueue.enqueue(i)
            walked += i
          }
        }
      }
    }
    ChiselError.warning("Pruned " + pruneCount + " nodes due to unconnected inputs")
  }

  def emitDef(node: Node): String = ""

  def levelChildren(root: Module) {
    root.level = 0;
    root.traversal = traversalIndex;
    traversalIndex = traversalIndex + 1;
    for(child <- root.children) {
      levelChildren(child)
      root.level = math.max(root.level, child.level + 1);
    }
  }

  def gatherChildren(root: Module): ArrayBuffer[Module] = {
    var result = new ArrayBuffer[Module]();
    for (child <- root.children)
      result = result ++ gatherChildren(child);
    result ++ ArrayBuffer[Module](root);
  }

  // go through every Module and set its clock and reset field
  def assignClockAndResetToModules {
    for (module <- Module.sortedComps.reverse) {
      if (module.clock == null)
        module.clock = module.parent.clock
      if (!module.hasExplicitReset)
        module.reset_=
    }
  }

  // go through every Module, add all clocks+resets used in it's tree to it's list of clocks+resets
  def gatherClocksAndResets {
    for (parent <- Module.sortedComps) {
      for (child <- parent.children) {
        for (reset <- child.resets.keys) {
          // create a reset pin in parent if reset does not originate in parent and 
          // if reset is not an output from one of parent's children
          if (reset.component != parent && !parent.children.contains(reset.component))
            parent.addResetPin(Bool(reset))

          // special case for implicit reset
          if (reset == Module.scope.implicitReset && parent == Module.topComponent)
            if (!parent.resets.contains(reset))
              parent.resets += (reset -> reset)
        }
      }
    }
  }

  def connectResets {
    for (parent <- Module.sortedComps) {
      for (child <- parent.children) {
        for (reset <- child.resets.keys) {
          if (child.resets(reset).inputs.length == 0)
            if (parent.resets.contains(reset))
              child.resets(reset).inputs += parent.resets(reset)
            else
              child.resets(reset).inputs += reset
        }
      }
    }
  }

  def nameRsts {
    for (comp <- Module.sortedComps) {
      for (rst <- comp.resets.keys) {
        if (!comp.resets(rst).named)
            comp.resets(rst).setName(rst.name)
      }
    }
  }

  // walk forward from root register assigning consumer clk = root.clock
/* XXX to re-implement
  def createClkDomain(root: Node, walked: ArrayBuffer[Node]) = {
    val dfsStack = new Stack[Node]
    walked += root; dfsStack.push(root)
    val clock = root.clock
    while(!dfsStack.isEmpty) {
      val node = dfsStack.pop
      for (consumer <- node.consumers) {
        if (!consumer.isInstanceOf[Delay] && !walked.contains(consumer)) {
          val c1 = consumer.clock
          val c2 = clock
          if(!(consumer.clock == null || consumer.clock == clock)) {
            ChiselError.warning({consumer.getClass + " " + emitRef(consumer) + " " + emitDef(consumer) + "in module" +
                                 consumer.component + " resolves to clock domain " + 
                                 emitRef(c1) + " and " + emitRef(c2) + " traced from " + root.name})
          } else { consumer.clock = clock }
          walked += consumer
          dfsStack.push(consumer)
        }
      }
    }
  }
 */

  def elaborate(c: Module): Unit = {
    Module.setAsTopComponent(c)

    /* XXX If we call nameAll here and again further down, we end-up with
     duplicate names in the generated C++.
    nameAll(c) */

    Module.components.foreach(_.elaborate(0));

    /* XXX We should name all signals before error messages are generated
     so as to give a clue where problems are showing up but that interfers
     with the *bindings* (see later comment). */
    for (c <- Module.components)
      c.markComponent();
    // XXX This will create nodes after the tree is traversed!
    // XXX c.genAllMuxes;
    // XXX verify all wires are assigned and have default values.
    transform(c, preElaborateTransforms)
    Module.components.foreach(_.postMarkNet(0));
    ChiselError.info("// COMPILING " + c + "(" + c.children.length + ")");
    // Module.assignResets()

    levelChildren(c)
    Module.sortedComps = gatherChildren(c).sortWith(
      (x, y) => (x.level < y.level || (x.level == y.level && x.traversal < y.traversal)));

    assignClockAndResetToModules
    Module.sortedComps.map(_.addDefaultReset)
//XXX    c.addClockAndReset
    gatherClocksAndResets
    connectResets

    ChiselError.info("started width inference")
    val accessibleNodes = new ArrayBuffer[Node]
    GraphWalker.tarjan(c.outputs(), {node => node.inferWidth.forward(node)})
    ChiselError.info("finished width inference")
    ChiselError.info("start width checking")
    c.forceMatchingWidths;
    ChiselError.info("finished width checking")
    ChiselError.info("started flattenning")
    ChiselError.checkpoint()

    /* *collectNodesIntoComp* associates components to nodes that were
     created after the call tree has been executed (ie. in genMuxes
     and forceMatchWidths).

     The purpose of *collectNodesIntoComp* is to insure user-defined
     transforms will be able to query a component for all its nodes
     and a node for its component.

     Technically all user-defined transforms are responsible to update
     nodes and component correctly or call collectNodesIntoComp on return.
     */
    ChiselError.info("resolving nodes to the components")
    collectNodesIntoComp(initializeDFS)
    ChiselError.info("finished resolving")

    // two transforms added in Mem.scala (referenced and computePorts)
    ChiselError.info("started transforms")
    transform(c, transforms)
    ChiselError.info("finished transforms")

    Module.sortedComps.map(_.nodes.map(_.addConsumers))
/* XXX re-implement clockdomains.
    val clkDomainWalkedNodes = new ArrayBuffer[Node]
    for (comp <- Module.sortedComps)
      for (node <- comp.nodes)
        if (node.isInstanceOf[RegDelay])
            createClkDomain(node, clkDomainWalkedNodes)
 */
    ChiselError.checkpoint()

    /* We execute nameAll after traceNodes because bindings would not have been
       created yet otherwise. */
    nameAll(c)
    nameRsts

    for (comp <- Module.sortedComps ) {
      // remove unconnected outputs
      pruneUnconnectedIOs(comp)
    }

    ChiselError.checkpoint()

    if(!Module.dontFindCombLoop) {
      ChiselError.info("checking for combinational loops")
      c.findCombLoop();
      ChiselError.checkpoint()
      ChiselError.info("NO COMBINATIONAL LOOP FOUND")
    }
    if(Module.saveComponentTrace) {
      printStack
    }
  }

  def compile(c: Module, flags: String = null): Unit = { }

  def checkPorts(topC: Module) {

    def prettyPrint(n: Node, c: Module) {
      val dir = if (n.asInstanceOf[IOBound].dir == INPUT) "Input" else "Output"
      val portName = n.name
      val compName = c.name
      val compInstName = c.moduleName
      ChiselError.warning(dir + " port " + portName
        + " is unconnected in module " + compInstName + " " + compName)
    }

    for (c <- Module.components) {
      if (c != topC) {
        for ((n,i) <- c.io.flatten) {
          val node = i.node
          if (node.inputs.length == 0) {
            prettyPrint(node, c)
          }
        }
      }
    }

  }

  /** Prints the call stack of Component as seen by the push/pop runtime. */
  protected def printStack {
    var res = ""
    for((i, c) <- Module.scope.printStackStruct){
      res += (genIndent(i) + c.moduleName + " " + c.name + "\n")
    }
    ChiselError.info(res)
  }

}


