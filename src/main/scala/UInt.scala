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
import Node._
import ChiselError._

object UInt {
  /* Implementation Note: scalac does not allow multiple overloaded
   method with default parameters so we define the following four
   methods to create UInt from litterals (with implicit and explicit
   widths) and reserve the default parameters for the "direction" method.
   */
  def apply(x: Int): UInt = UInt(Literal(x))
  def apply(x: Int, width: Int): UInt = UInt(Literal(x, width))
  def apply(x: String): UInt = UInt(Literal(x, -1))
  def apply(x: String, width: Int): UInt = UInt(Literal(x, width))
  def apply(x: String, base: Char): UInt = UInt(Literal(x, base, -1))
  def apply(x: String, base: Char, width: Int): UInt
    = UInt(Literal(x, base, width))

  def apply(dir: IODirection = NODIRECTION, width: Int = -1): UInt
    = new UInt(new IOBound(dir, width))

  def apply(node: Node): UInt = new UInt(node)
}


class UInt(node: Node = null) extends Bits(node) /* with Numeric[UInt] */ {
  type T = UInt;

  def toBool(): Bool = {
    Bool(this.node)
  }

  def :=(src: UInt) {
    this procAssign src.node;
  }

  // unary operators
  def zext: UInt = UInt(0, 1) ## this
  def unary_-(): SInt = SignRev(this.zext)
  def unary_!(): Bool = LogicalNeg(this)

  // arithmetic operators

  def << (right: UInt): UInt = LeftShiftOp(this, right)
  def >> (right: UInt): UInt = RightShift(this, right)

// XXX deprecated?  def ?  (b: UInt): UInt = newBinaryOp(b, "?");

  def + (right: UInt): UInt = AddOp(this, right)
  def -  (right: UInt): UInt = SubOp(this, right)
  def * (right: UInt): UInt = MulOp(this, right)
  def % (right: UInt): UInt = Rem(this, right)
  def / (right: UInt): UInt = Div(this, right)

  // order operators
  def >  (right: UInt): Bool = GtrOp(this, right)
  def <  (right: UInt): Bool = LtnOp(this, right)
  def <= (right: UInt): Bool = LteOp(this, right)
  def >= (right: UInt): Bool = GteOp(this, right)

  //UInt op SInt arithmetic
  def + (right: SInt): SInt = AddOp(SInt(this.zext.node), right)
  def - (right: SInt): SInt = SubOp(SInt(this.zext.node), right)
  def * (right: SInt): SInt = MulSU(right, this.zext)
  def % (right: SInt): SInt = RemUS(this.zext, right)
  def / (right: SInt): SInt = DivUS(this.zext, right)
}
