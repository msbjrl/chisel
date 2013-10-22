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

import scala.collection.immutable.Vector
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Stack
import java.io.PrintStream

import Node._;
import ChiselError._;

object Node {
  def sprintf(message: String, args: Node*): Bits = {
    UInt(new Sprintf(message, args))
  }

  var isCoercingArgs = true;

  /** defines the Scope of conditions set through *when* calls */
  val conds = new Stack[Bool]();
  conds.push(Bool(true));
  def genCond(): Node = conds.top.node;

  /** defines the Scope of Bits set by *switch* call and used for *is* */
  val keys  = new Stack[Bits]();

  /* clk is initialized in Module.initChisel */
  var clk: Node = null

}

/** *Node* defines the root class of the class hierarchy for
  a [Composite Pattern](http://en.wikipedia.org/wiki/Composite_pattern).

  A digital logic graph is encoded as adjacency graph where instances
  of *Node* describe vertices and *inputs*, *consumers* member fields
  are used to traverse the directed graph respectively backward (from
  output to input) and forward (from input to output).
  */
abstract class Node extends nameable {
  var sccIndex = -1
  var sccLowlink = -1
  var walked = false;
  /* Assigned in Binding and Mod.reset */
  var component: Module = Module.getComponent();
  var flattened = false;
  var isTypeNode = false;
  var depth = 0;
  def componentOf: Module = if (Module.isEmittingComponents && component != null) component else Module.topComponent
  var isSigned = false;
  var width = -1;
  var index = -1;
  var isFixedWidth = false;
  val consumers = new ArrayBuffer[Node]; // mods that consume one of my outputs
  val inputs = new ArrayBuffer[Node];
  def traceableNodes: Array[Node] = Array[Node]();

  var nameHolder: nameable = null;
  var isClkInput = false;
  var inferCount = 0;
  var genError = false;
  var stack: Array[StackTraceElement] = null;
  var line: StackTraceElement = findFirstUserLine(Thread.currentThread().getStackTrace) getOrElse Thread.currentThread().getStackTrace()(0)
  var isScanArg = false
  var isPrintArg = false
  def isMemOutput: Boolean = false
  var prune = false
  var driveRand = false
  var clock: Clock = null

  var canBeUsedAsDefault = false

  Module.nodes += this

  def setIsSigned {
    isSigned = true
  }

  /* Returns true is this node is bound to an 'always true' variable
   and false otherwise. */
  def boundToTrue: Boolean = {
    if(this.inputs.length == 0) {
      false
    } else {
      this.inputs(0) match {
        case l: Literal => l.value == 1
        case any       => false
      }
    }
  }

  def inferWidth(): Width

  def isByValue: Boolean = true;

  def nameIt (path: String) {
    if( !named ) {
      /* If the name was set explicitely through *setName*,
       we don't override it. */
      name = path;
    }
  }

  def clearlyEquals(x: Node): Boolean = this == x

/*
  // TODO: SHOULD AGREE WITH isLit
  def signed: this.type = {
    val res = SInt()
    res := this.asInstanceOf[SInt];
    res.isSigned = true;
    res.asInstanceOf[this.type]
  }

  def bitSet(off: UInt, dat: UInt): UInt = {
    val bit = UInt(1, 1) << off;
    (this.asInstanceOf[UInt] & ~bit) | (dat << off);
  }
*/
  // TODO: MOVE TO WIRE
  def assign(src: Node) {
    if (inputs.length > 0) {
      inputs(0) = src;
    } else {
      inputs += src;
    }
  }

  def asDirectionless(): this.type = this

  def asInput(): this.type = this

  def asOutput(): this.type = this

  def flip(): this.type = this

  private var _isIo = false
  def isIo = _isIo
  def isIo_=(isIo: Boolean) = _isIo = isIo
  def isReg: Boolean = false;
  def isUsedByRam: Boolean = {
    for (c <- consumers)
      if (c.isRamWriteInput(this)) {
        return true;
      }
    return false;
  }
  def isRamWriteInput(i: Node): Boolean = false;

  def isInObject: Boolean =
    (isIo && (Module.isIoDebug || component == Module.topComponent)) ||
    Module.topComponent.debugs.contains(this) ||
    isReg || isUsedByRam || Module.isDebug || isPrintArg || isScanArg;

  def isInVCD: Boolean = width > 0 &&
    ((isIo && isInObject) || isReg || (Module.isDebug && !name.isEmpty))

  /** Prints all members of a node and recursively its inputs up to a certain
    depth level. This method is purely used for debugging. */
  def printTree(writer: PrintStream, depth: Int = 4, indent: String = ""): Unit = {
    if (depth < 1) return;
    writer.println(indent + getClass + " width=" + getWidth + " #inputs=" + inputs.length);
    writer.println("sccIndex: " + sccIndex)
    writer.println("sccLowlink: " + sccLowlink)
    writer.println("walked: " + walked)
    writer.println("component: " + component)
    writer.println("flattened: " + flattened)
    writer.println("depth: " + depth)
    writer.println("isSigned: " + isSigned)
    writer.println("width: " + width)
    writer.println("index: " + index)
    writer.println("isFixedWidth: " + isFixedWidth)
    writer.println("consumers.length: " + consumers.length)
    writer.println("nameHolder: " + nameHolder)
    writer.println("isClkInput: " + isClkInput)
    writer.println("inferCount: " + inferCount)
    writer.println("genError: " + genError)
    writer.println("stack.length: " + (if(stack != null) { stack.length } else { 0 }))
    writer.println("line: " + line)
    writer.println("isScanArg: " + isScanArg)
    writer.println("isPrintArg: " + isPrintArg)
    writer.println("isMemOutput: " + isMemOutput)
    for (in <- inputs) {
      if (in == null) {
        writer.println("null");
      } else {
        in.printTree(writer, depth-1, indent + "  ");
      }
    }
  }

/* XXX
  def forceMatchingWidths { }

  def matchWidth(w: Int): Node = {
    if (w > this.width) {
      val topBit = if (isSigned) NodeExtract(this, this.width-1) else Literal(0,1)
      topBit.infer
      val fill = NodeFill(w - this.width, topBit); fill.infer
      val res = CatOp(fill, this); res.infer
      res
    } else if (w < this.width) {
      val res = NodeExtract(this, w-1,0); res.infer
      res
    } else {
      this
    }
  }
 */

  def setName(n: String) {
    name = n
    named = true;
  }

  def setIsClkInput {};

  var isWidthWalked = false;

  def getWidth(): Int = {
    val w = width
    // XXX infer width at this point.
    throw new Exception("getWidth")
    w
  }

  def removeTypeNodes() {
    for(i <- 0 until inputs.length) {
      if(inputs(i) == null){
        val error = new ChiselError(() => {"NULL Input for " + this.getClass + " " + this + " in Module " + component}, this.line);
        if (!ChiselErrors.contains(error)) {
          ChiselErrors += error
        }
      }
      else if(inputs(i).isTypeNode) {
        inputs(i) = inputs(i).getNode;
      }
    }
  }
  def getNode(): Node = {
    if(!isTypeNode || inputs.length == 0) {
      this
    } else {
      inputs(0).getNode
    }
  }

  def addConsumers() {
    for ((i, off) <- inputs.zipWithIndex) {
      /* By construction we should not end-up with null inputs. */
      assert(i != null, ChiselError.error("input " + off
        + " of " + inputs.length + " for node " + this + " is null"))
      if(!i.consumers.contains(this)) {
        i.consumers += this;
      }
    }
  }

/** XXX good idea. deprecated?
  def extract (widths: Array[Int]): List[UInt] = {
    var res: List[UInt] = Nil;
    var off = 0;
    for (w <- widths) {
      res  = UInt(this)(off + w - 1, off) :: res;
      off += w;
    }
    res.reverse
  }

  def extract (b: Bundle): List[Node] = {
    var res: List[Node] = Nil;
    var off = 0;
    for ((n, io) <- b.flatten) {
      if (io.dir == OUTPUT) {
        val w = io.width;
        res  = Extract(UInt(this), UInt(off + w - 1), UInt(off)).node :: res;
        off += w;
      }
    }
    res.reverse
  }
  */

  def maybeFlatten: Seq[Node] = {
    this match {
      case b: Bundle =>
        val buf = ArrayBuffer[Node]();
        for ((n, e) <- b.flatten) buf += e.node;
        buf
      case o        =>
        Array[Node](getNode);
    }
  }
  def emitIndex(): Int = {
    if (index == -1) {
      index = componentOf.nextIndex;
    }
    index
  }


  override def toString(): String = {
    var str = "connect to " + inputs.length + " inputs: ("
    var sep = ""
    for( i <- inputs ) {
      str = (str + sep + (if (i.name != null) i.name else "?")
        + "[" + i.getClass.getName + "]"
        + " in " + (if (i.component != null) i.component.getClass.getName else "?"))
      sep = ", "
    }
    str
  }

}
