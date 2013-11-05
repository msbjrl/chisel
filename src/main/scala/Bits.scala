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

import scala.math.max

import ChiselError._

/* backward compatibility */
object Bits {
  def apply(x: Int): UInt = UInt(x)
  def apply(x: Int, width: Int): UInt = UInt(x, width)
  def apply(x: String): UInt = UInt(x)
  def apply(x: String, width: Int): UInt = UInt(x, width)

  def apply(dir: IODirection = NODIRECTION, width: Int = -1): UInt
    = UInt(dir, width);
}


/** Base class for Chisel built-in types like UInt and SInt.

  Implementation Note:
  A no-argument constructor is different from a constructor with default
  arguments. Since we will use reflection to create instances of subclasses
  of Bits, we must use the no-argument constructor form.
  */
abstract class Bits extends Data {

  var node: Node = null

  /* Node to attach the default value while building a mux tree through
   conditional assigments. */
  var default: Node = null

  Module.ioMap += ((this, Module.ioCount));
  Module.ioCount += 1;

  override def asDirectionless(): this.type = {
    if( node != null ) node.asDirectionless()
    this
  }

  override def asInput(): this.type = {
    if( node != null ) node.asInput()
    this
  }

  override def asOutput(): this.type = {
    if( node != null ) node.asOutput()
    this
  }

  override def flatten: Array[(String, Bits)] = Array((name, this));

  override def flip(): this.type = {
    if( node != null ) node.flip()
    this
  }

  override def fromBits( bits: Bits ): this.type = {
    this.node = bits.node
    this
  }

  /** Infer widths on the partially constructed graph rooted at this
    and returns the width of this node.
  */
  def getWidth(): Int = {
    GraphWalker.tarjan(node :: Nil, {node => node.inferWidth().forward(node)})
    node.width
  }

  /** Returns ``true`` when this Bits instance is bound to a ``Node``
    that generates a constant signal.
    */
  def isConst: Boolean = {
    node != null && node.isInstanceOf[Literal]
  }

  override def nameIt(name: String) = {
    if( node != null ) node.name = name
  }

  /* Assignment to this */
  override def procAssign(src: Node) {
    var result: Node = null
    if( Module.scope.isDefaultCond() ) {
      if( default != null ) {
        if( default.inputs(2) != null ) {
          /* We are dealing with a default assignement but the default
           position is already occupied. */
          ChiselError.warning("re-assignment to node under default condition.")
        } else {
          default.inputs(2) = src.lvalue()
        }
      } else {
        result = src.lvalue()
        if( node != null && node.assigned ) {
          /* We are dealing with a default assignement, there are no
           mux tree but the default position is already occupied. */
          ChiselError.warning("re-assignment to node under default condition.")
        }
      }
    } else {
      val result = new MuxOp(Module.scope.genCond(), src.lvalue(),
        if( node != null ) node.lvalue() else null)
      if( node == null ) {
        /* First assignment we construct a mux tree with a dangling
         default position. */
        default = result
      }
    }
    if( node != null ) {
      node.rvalue(result)
    } else {
      node = result
    }
  }

  override def toBits(): UInt = UInt(this.node)

  override def toString: String = {
    // XXX We cannot print the width here as it would computed the infered
    // width, hence change the computations. It might be possible to print
    // width_ but it seems to also have some underlying computations associated
    // to it.
    var str = (
      "/*" + (if (name != null && !name.isEmpty) name else "?")
        + (if (component != null) (" in " + component) else "") + "*/ "
        + getClass.getName + "("
        + "node=" + node
        + ", width=" + node.width)
    str = str + "))"
    str
  }

  /* The <> operator bulk connects interfaces of opposite direction between
   sibling modules or interfaces of same direction between parent/child modules.

   Bulk connections connect leaf ports using pathname matching. Connections
   are only made if one of the ports is non-null, allowing users to repeatedly
   bulk-connect partially filled interfaces.

   After all connections are made and the circuit is being elaborated,
   Chisel warns users if ports have other than exactly one connection to them.
  */
  override def <>(right: Data): Unit = {
    right match {
      case other: Bits => this <> other;
      case _ => super.<>(right)
    }
  }

  def <>(right: Bits) {
    node match {
      case leftBond: IOBound =>
        right.node match {
          case rightBond: IOBound => {
            if(leftBond.dir == INPUT && rightBond.dir == INPUT ) {
              leftBond.bind(rightBond)
            } else if (leftBond.dir == INPUT && rightBond.dir == OUTPUT ) {
              leftBond.bind(rightBond)
            } else if (leftBond.dir == OUTPUT && rightBond.dir == INPUT ) {
              rightBond.bind(leftBond)
            } else if (leftBond.dir == OUTPUT && rightBond.dir == OUTPUT ) {
              leftBond.bind(rightBond)
            }
          }
        }
    }
  }

/*XXX
  override def setIsClkInput {
    node.isClkInput = true
    this assign clk
  }
 */
  override def clone: this.type = {
    val res = this.getClass.newInstance.asInstanceOf[this.type];
    res.node = this.node;
    res
  }

/*

  override def forceMatchingWidths {
    if(inputs.length == 1 && inputs(0).width != width) {
      inputs(0) = inputs(0).matchWidth(width)
    }
  }
 */

  /** Assignment operator.

    The assignment operator can be called multiple times
   */
  def :=(src: Bits): Unit = {

    this procAssign src.node;
  }


  // bitwise operators
  // =================

  /** Extract a single Bool at index *bit*.
    */
  final def apply(bit: Int): Bool = Extract(this, UInt(bit))
  final def apply(bit: UInt): Bool = Extract(this, bit)

  /** Extract a range of bits */
  final def apply(hi: Int, lo: Int): UInt = Extract(this, UInt(hi), UInt(lo))
  final def apply(hi: UInt, lo: UInt): UInt = Extract(this, hi, lo)

/** can't define apply(range: (UInt, UInt)) because it gets same
  signature after type erasure. */
  final def apply(range: (Int, Int)): UInt = this(range._1, range._2)

  // to support implicit convestions
  override def ===(right: Data): Bool = {
    right match {
      case bits: Bits => Eql(this, bits)
      case _ => this === right.toBits
    }
  }

  def unary_~(): UInt = BitwiseRev(this)
  def andR(): Bool = ReduceAnd(this)
  def orR(): Bool = ReduceOr(this)
  def xorR(): Bool = ReduceXor(this)
  def != (right: Bits): Bool = Neq(this, right)
  def & (right: Bits): this.type = And(this, right)
  def | (right: Bits): this.type = Or(this, right)
  def ^ (right: Bits): this.type = Xor(this, right)

  def ##(right: Bits): UInt = Cat(this, right)
}


object BitwiseRev {
  def apply(opand: Bits): UInt = {
    UInt(
      if( opand.isConst ) {
        Literal((-opand.node.asInstanceOf[Literal].value - 1)
          & ((BigInt(1) << opand.node.width) - 1),
          opand.node.width)
      } else {
        new BitwiseRevOp(opand.node.lvalue())
      })
  }
}


object And {
  def apply[T <: Bits](left: T, right: Bits)(implicit m: Manifest[T]): T = {
    val op = if( left.isConst && right.isConst ) {
      Literal(left.node.asInstanceOf[Literal].value
        & right.node.asInstanceOf[Literal].value,
        max(left.node.width, right.node.width))
    } else {
      new AndOp(left.node.lvalue(), right.node.lvalue())
    }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}


object Eql {
  def apply[T <: Bits]( left: T, right: T): Bool = {
    Bool(
      if( left.isConst && right.isConst ) {
        Literal(if (left.node.asInstanceOf[Literal].value
          == right.node.asInstanceOf[Literal].value) 1 else 0)
      } else {
        new EqlOp(left.node.lvalue(), right.node.lvalue())
      })
  }
}


object Extract {

  def apply(opand: Bits, bit: UInt): Bool = Bool(apply(opand, bit, bit).node)

  // extract bit range
  def apply(opand: Bits, hi: Bits, lo: Bits): UInt = {
    UInt(
      if( opand.isConst && hi.isConst && lo.isConst ) {
        /* XXX Original code sets output width to input width,
         for no apparent reason?
        val w = if (opand.node.width == -1) (
          hi.node.asInstanceOf[Literal].value.toInt
            - lo.node.asInstanceOf[Literal].value.toInt + 1)
                else opand.node.width;
         */
        val w = (hi.node.asInstanceOf[Literal].value.toInt
            - lo.node.asInstanceOf[Literal].value.toInt + 1)
        Literal((opand.node.asInstanceOf[Literal].value
          >> lo.node.asInstanceOf[Literal].value.toInt)
          & ((BigInt(1) << w) - BigInt(1)), w)
      } else if( opand.isConst ) {
        /* XXX Why would this be better than an ExtractOp? */
        val rsh = new RightShiftOp(opand.node.lvalue(), lo.node.lvalue())
        val hiMinusLoPlus1 = new AddOp(
          new SubOp(hi.node.lvalue(), lo.node.lvalue()), Literal(1))
        val mask = new SubOp(
          new LeftShiftOp(Literal(1), hiMinusLoPlus1), Literal(1))
        new AndOp(rsh, mask)
      } else {
        new ExtractOp(opand.node.lvalue(), hi.node.lvalue(), lo.node.lvalue())
      })
  }
}

object Add {
  def apply[T <: Bits]( left: T, right: T)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        Literal(left.node.asInstanceOf[Literal].value
          + right.node.asInstanceOf[Literal].value,
          max(left.node.width, right.node.width) + 1) // XXX does not always need carry.
      } else {
        new AddOp(left.node.lvalue(), right.node.lvalue())
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}

object Fill {

  def apply(n: Int, opand: Bits): UInt = apply(opand, n)

  def apply(opand: Bits, n: Int): UInt = {
    UInt(
      if( opand.isConst ) {
        var c = BigInt(0)
        val w = opand.node.width
        val a = opand.node.asInstanceOf[Literal].value
        for (i <- 0 until n)
          c = (c << w) | a
        Literal(c, n * w)
      } else if( n == 1 ) {
        opand.node.lvalue()
      } else {
        new FillOp(opand.node.lvalue(), n)
      })
  }
}


object LeftShift {
  def apply[T <: Bits](left: T, right: UInt)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        Literal(left.node.asInstanceOf[Literal].value
          << right.node.asInstanceOf[Literal].value.toInt,
          left.node.width + right.node.width)
      } else {
        new LeftShiftOp(left.node.lvalue(), right.node.lvalue())
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}


object LogicalNeg {
  def apply( opand: Bits): Bool = {
    Bool(
      if( opand.isConst ) {
        if( opand.node.asInstanceOf[Literal].value == 0) Literal(1)
        else Literal(0)
      } else {
        new LogicalNegOp(opand.node.lvalue())
      })
  }
}


object RightShift {
  def apply[T <: Bits](left: T, right: UInt)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        if( left.isInstanceOf[UInt] ) {
          Literal(left.node.asInstanceOf[Literal].value
            >> right.node.asInstanceOf[Literal].value.toInt,
            left.node.width - right.node.width)
        } else {
          /* XXX BigInt signed right shift? */
          Literal(left.node.asInstanceOf[Literal].value
            >> right.node.asInstanceOf[Literal].value.toInt,
            left.node.width - right.node.width)
        }
      } else {
        if( left.isInstanceOf[UInt] ) {
          new RightShiftOp(left.node.lvalue(), right.node.lvalue())
        } else {
          new RightShiftSOp(left.node.lvalue(), right.node.lvalue())
        }
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}


object Neq {
  def apply[T <: Bits]( left: T, right: T): Bool = {
    Bool(
      if( left.isConst && right.isConst ) {
        Literal(if (left.node.asInstanceOf[Literal].value
          != right.node.asInstanceOf[Literal].value) 1 else 0)
      } else {
        new NeqOp(left.node.lvalue(), right.node.lvalue())
      })
  }
}


object Or {
  def apply[T <: Bits](left: T, right: Bits)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        Literal(left.node.asInstanceOf[Literal].value
          | right.node.asInstanceOf[Literal].value,
          max(left.node.width, right.node.width))
      } else {
        new OrOp(left.node.lvalue(), right.node.lvalue())
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}


object Xor {
  def apply[T <: Bits](left: T, right: Bits)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        Literal(left.node.asInstanceOf[Literal].value
          ^ right.node.asInstanceOf[Literal].value,
          max(left.node.width, right.node.width))
      } else {
        new XorOp(left.node.lvalue(), right.node.lvalue())
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}


object ReduceAnd {
  def apply[T <: Bits](opand: T): Bool = {
    val op = new ReduceAndOp(opand.node.lvalue())
    Bool(op)
  }
}

object andR {
    def apply(x: Bits): Bool = ReduceAnd(x)
}


object ReduceOr {
  def apply[T <: Bits](opand: T): Bool = {
    val op = new ReduceOrOp(opand.node.lvalue())
    Bool(op)
  }
}

object orR {
    def apply(x: Bits): Bool = ReduceOr(x)
}


object ReduceXor {
  def apply[T <: Bits](opand: T): Bool = {
    val op = new ReduceXorOp(opand.node.lvalue())
    Bool(op)
  }
}

object xorR {
    def apply(x: Bits): Bool = ReduceXor(x)
}

object Sub {
  def apply[T <: Bits]( left: T, right: T)(implicit m: Manifest[T]): T = {
    val op =
      if( left.isConst && right.isConst ) {
        Literal(left.node.asInstanceOf[Literal].value
          - right.node.asInstanceOf[Literal].value,
          max(left.node.width, right.node.width) + 1) // XXX unnecessary carry.
      } else {
        new SubOp(left.node.lvalue(), right.node.lvalue())
      }
    val result = m.runtimeClass.newInstance.asInstanceOf[T]
    result.node = op
    result
  }
}
