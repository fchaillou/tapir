package tapir.docs.openapi

import tapir.{Schema => SSchema, MediaType => SMediaType, _}
import tapir.openapi.OpenAPI.ReferenceOr
import tapir.openapi.{Schema => OSchema, MediaType => OMediaType, _}

object EndpointToOpenAPIDocs {
  def toOpenAPI(title: String, version: String, es: Seq[Endpoint[_, _, _]]): OpenAPI = {
    OpenAPI(
      info = Info(title, None, None, version),
      servers = None,
      paths = paths(es),
      components = components(es)
    )
  }

  private def paths(es: Seq[Endpoint[_, _, _]]): Map[String, PathItem] = {
    es.map(pathItem).groupBy(_._1).mapValues(_.map(_._2).reduce(mergePathItems))
  }

  private def pathItem(e: Endpoint[_, _, _]): (String, PathItem) = {
    import Method._

    val pathComponents = e.input.inputs.flatMap {
      case EndpointInput.PathCapture(_, name, _, _) => Some(s"{${name.getOrElse("-")}}")
      case EndpointInput.PathSegment(s)             => Some(s)
      case _                                        => None
    }
    // TODO parametrize the class with customizable id generation
    val defaultId = s"${pathComponents.mkString("-")}-${e.method.m.toLowerCase}"

    val pathItem = PathItem(
      None,
      None,
      get = if (e.method == GET) Some(operation(defaultId, e)) else None,
      put = if (e.method == PUT) Some(operation(defaultId, e)) else None,
      post = if (e.method == POST) Some(operation(defaultId, e)) else None,
      delete = if (e.method == DELETE) Some(operation(defaultId, e)) else None,
      options = if (e.method == OPTIONS) Some(operation(defaultId, e)) else None,
      head = if (e.method == HEAD) Some(operation(defaultId, e)) else None,
      patch = if (e.method == PATCH) Some(operation(defaultId, e)) else None,
      trace = if (e.method == TRACE) Some(operation(defaultId, e)) else None,
      servers = None,
      parameters = None
    )

    ("/" + pathComponents.mkString("/"), pathItem)
  }

  private def mergePathItems(p1: PathItem, p2: PathItem): PathItem = {
    PathItem(
      None,
      None,
      get = p1.get.orElse(p2.get),
      put = p1.put.orElse(p2.put),
      post = p1.post.orElse(p2.post),
      delete = p1.delete.orElse(p2.delete),
      options = p1.options.orElse(p2.options),
      head = p1.head.orElse(p2.head),
      patch = p1.patch.orElse(p2.patch),
      trace = p1.trace.orElse(p2.trace),
      servers = None,
      parameters = None
    )
  }

  private def operation(defaultId: String, e: Endpoint[_, _, _]): Operation = {

    val parameters = e.input.inputs.flatMap {
      case EndpointInput.Query(n, tm, d, ex) =>
        Some(
          Parameter(n,
                    ParameterIn.Query,
                    d,
                    Some(!tm.isOptional),
                    None,
                    None,
                    None,
                    None,
                    None,
                    Right(schemaToSchema(tm.schema)),
                    ex.flatMap(exampleValue(tm, _)),
                    None,
                    None))
      case EndpointInput.PathCapture(tm, n, d, ex) =>
        Some(
          Parameter(
            n.getOrElse("?"), // TODO
            ParameterIn.Path,
            d,
            Some(true),
            None,
            None,
            None,
            None,
            None,
            Right(schemaToSchema(tm.schema)),
            ex.flatMap(exampleValue(tm, _)),
            None,
            None
          ))
      case _ => None
    }

    val responses: Map[ResponsesKey, ReferenceOr[Response]] =
      List(
        outputToResponse(e.output).map { r =>
          ResponsesCodeKey(200) -> Right(r)
        },
        outputToResponse(e.errorOutput).map { r =>
          ResponsesDefaultKey -> Right(r)
        }
      ).flatten.toMap

    Operation(noneIfEmpty(e.tags.toList),
              e.summary,
              e.description,
              defaultId,
              noneIfEmpty(parameters.toList.map(Right(_))),
              None,
              responses,
              None,
              None)
  }

  private def outputToResponse(o: EndpointIO.Multiple[_]): Option[Response] = {
    o.ios.headOption.map {
      case EndpointIO.Body(m, d, e) => Response(d.getOrElse(""), None, Some(typeMapperToMediaType(m, e)))
      case _                        => Response("", None, None)
    }
  }

  private def typeMapperToMediaType[T, M <: SMediaType](o: TypeMapper[T, M], example: Option[T]): Map[String, OMediaType] = {
    Map(o.mediaType.mediaType -> OMediaType(Some(Right(schemaToSchema(o.schema))), example.flatMap(exampleValue(o, _)), None, None))
  }

  private def exampleValue[T](tm: TypeMapper[T, _], e: T): Option[ExampleValue] = tm.toOptionalString(e).map(ExampleValue)

  private def components(es: Seq[Endpoint[_, _, _]]): Option[Components] = None

  private def noneIfEmpty[T](l: List[T]): Option[List[T]] = if (l.isEmpty) None else Some(l)

  private def schemaToSchema(schema: SSchema): OSchema = {
    schema match {
      case SSchema.SInt =>
        OSchema(SchemaType.Integer)
      case SSchema.SString =>
        OSchema(SchemaType.String)
      case SSchema.SObject(fields, required) =>
        OSchema(SchemaType.Object).copy(
          required = Some(required.toList),
          properties = Some(
            fields.map {
              case (fieldName, fieldSchema) =>
                fieldName -> schemaToSchema(fieldSchema)
            }.toMap
          )
        )
    }
  }
}