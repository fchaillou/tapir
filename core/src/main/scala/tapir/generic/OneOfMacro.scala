package tapir.generic

import tapir.SchemaFor

import scala.annotation.tailrec
import scala.reflect.macros.blackbox

object OneOfMacro {
  // http://onoffswitch.net/extracting-scala-method-names-objects-macros/

  def oneOfMacro[E: c.WeakTypeTag, V: c.WeakTypeTag](
      c: blackbox.Context
  )(extractor: c.Expr[E => V], asString: c.Expr[V => String])(mapping: c.Expr[(V, SchemaFor[_])]*): c.Expr[SchemaFor[E]] = {
    import c.universe._

    @tailrec
    def resolveFunctionName(f: Function): String = {
      f.body match {
        // the function name
        case t: Select => t.name.decodedName.toString

        case t: Function =>
          resolveFunctionName(t)

        // an application of a function and extracting the name
        case t: Apply if t.fun.isInstanceOf[Select] =>
          t.fun.asInstanceOf[Select].name.decodedName.toString

        // curried lambda
        case t: Block if t.expr.isInstanceOf[Function] =>
          val func = t.expr.asInstanceOf[Function]

          resolveFunctionName(func)

        case _ =>
          throw new RuntimeException("Unable to resolve function name for expression: " + f.body)
      }
    }

    val name = resolveFunctionName(extractor.tree.asInstanceOf[Function])
    val schemaForE =
      q"""import tapir.Schema._
          val rawMapping = Map(..$mapping)
          val discriminator = Discriminator($name, rawMapping.collect{case (k, sf)=> $asString.apply(k) -> SRef(sf.schema.asInstanceOf[SObject].info)})
          SchemaFor(SCoproduct(rawMapping.values.map(_.schema).toSet, Some(discriminator)))"""
    c.Expr[SchemaFor[E]](schemaForE)
  }
}
