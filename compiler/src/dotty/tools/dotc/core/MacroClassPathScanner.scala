package dotty.tools
package dotc
package core

import dotty.tools.dotc.ast.tpd
import dotty.tools.backend.sjs.JSEncoding.{encodeClassName, toParamOrResultTypeRef, toTypeRef}
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.TypeErasure.fullErasure
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.inlines.Inlines
import dotty.tools.dotc.quoted.Interpreter.Call
import dotty.tools.dotc.sjsmacros.{MacroRuntimeExports, MacroRuntimeId}
import dotty.tools.sjs.ir
import dotty.tools.sjs.ir.{ClassKind, Position, Trees => js, Types => jstpe, WellKnownNames => jswkn}
import dotty.tools.sjs.ir.Names.{ClassName, LocalName, MethodName, SimpleMethodName}
import dotty.tools.sjs.ir.OriginalName.NoOriginalName
import dotty.tools.sjs.ir.Version.Unversioned

import java.io.ByteArrayOutputStream
import scala.collection.mutable
import scala.util.control.NonFatal

import Contexts.*
import Flags.*
import SymDenotations.*
import Symbols.*

object MacroClassPathScanner:

  final case class MacroEntryPoint(
    id: String,
    implementationOwnerClassName: ClassName,
    implementationMethod: js.MethodIdent,
    implementationResultType: jstpe.Type,
    implementationParamTypes: IArray[jstpe.Type],
  )

  final case class SerializedMacroEntryPointsIR(
    packageName: String,
    path: String,
    bytes: Array[Byte],
  )

  def serializedMacroEntryPointsIRInPackage(packageName: String)(using Context): List[SerializedMacroEntryPointsIR] =
    macroEntryPointsClassDefsInPackage(packageName).map { classDef =>
      SerializedMacroEntryPointsIR(
        packageName = packageName,
        path = entryPointsIRPath(classDef.name.name),
        bytes = serializeClassDef(classDef),
      )
    }

  /** The macros of a package, one entry each (build-time prelinking chooses which to link). */
  def macroEntryPoints(packageName: String)(using Context): List[MacroEntryPoint] =
    macroEntryPointsInPackage(packageName)

  /** The entry-points class for the given subset of a package's macros. */
  def serializedMacroEntryPointsIR(packageName: String, entries: List[MacroEntryPoint])(using Context): List[SerializedMacroEntryPointsIR] =
    if entries.isEmpty then Nil
    else
      implicit val pos: Position = Position.NoPosition
      val classDef = macroEntryPointsModuleClassDef(packageName, generatedClassName(packageName, "MacroEntryPoints$"), entries)
      List(SerializedMacroEntryPointsIR(packageName, entryPointsIRPath(classDef.name.name), serializeClassDef(classDef)))

  private def implementationSymbolOf(symbol: TermSymbol)(using Context): Option[TermSymbol] =
    val body = Inlines.bodyToInline(symbol)
    if body.isEmpty then None
    else extractImplementation(body)

  private def macroEntryPointsInPackage(packageName: String)(using Context): List[MacroEntryPoint] =
    findMacroSymbolsInPackage(packageName)
      .sortBy(MacroRuntimeId.stableId)
      .flatMap { symbol =>
        implementationSymbolOf(symbol).map { implementation =>
          given Position = Position.NoPosition
          val implementationOwnerClassName = encodeClassName(implementation.owner)
          val implementationMethod = methodIdentOf(implementation)
          val implementationResultType = irTypeOfMethodResult(implementationMethod)
          val implementationParamTypes = irTypesOfMethodParams(implementationMethod)
          MacroEntryPoint(
            id = MacroRuntimeId.stableId(symbol),
            implementationOwnerClassName = implementationOwnerClassName,
            implementationMethod = implementationMethod,
            implementationResultType = implementationResultType,
            implementationParamTypes = implementationParamTypes,
          )
        }
      }

  private def extractImplementation(tree: tpd.Tree)(using Context): Option[TermSymbol] =
    import tpd.*

    tree match
      case Splice(code) =>
        extractImplementation(code).orElse(extractCallTarget(code))
      case closureDef(ddef @ DefDef(_, ValDefs(_ :: Nil) :: Nil, _, _))
          if ddef.symbol.info.isContextualMethod =>
        extractImplementation(ddef.rhs)
      case Inlined(_, _, expansion) =>
        extractImplementation(expansion)
      case Block(stats, expr) =>
        extractImplementation(expr)
          .orElse(stats.reverseIterator.map(extractImplementation).collectFirst { case Some(target) => target })
      case Typed(expr, _) =>
        extractImplementation(expr)
      case NamedArg(_, arg) =>
        extractImplementation(arg)
      case _ if tree.isTerm =>
        extractCallTarget(tree)
      case _ =>
        None

  private def extractCallTarget(tree: tpd.Tree)(using Context): Option[TermSymbol] =
    import tpd.*

    tree match
      case Call(fn, _) if fn.symbol.is(Method) =>
        Some(fn.symbol.asTerm)
      case Call(fn, _) if fn.symbol.isStatic =>
        Some(fn.symbol.asTerm)
      case Call(fn, _) if fn.symbol.is(Module) =>
        Some(fn.symbol.asTerm)
      case _ =>
        None

  private def findMacroSymbolsInPackage(packageName: String)(using Context): List[TermSymbol] =
    findAllSymbolsInPackage(packageName).filter(_.is(Macro))

  private def findAllSymbolsInPackage(packageName: String)(using Context): List[TermSymbol] =
    findAllSymbolsFrom(packageName :: Nil)

  private def findAllSymbolsFrom(packageRoots: List[String])(using Context): List[TermSymbol] =
    val seenPackages = mutable.HashSet.empty[String]
    val seenOwners = mutable.HashSet.empty[ClassSymbol]
    val seenMethods = mutable.LinkedHashSet.empty[TermSymbol]
    val classPath = ctx.platform.classPath

    def scanPackage(packageName: String): Unit =
      if seenPackages.add(packageName) then
        try
          val pkg =
            if packageName.isEmpty then defn.EmptyPackageVal
            else requiredPackage(packageName)
          scanOwner(pkg.moduleClass.asClass)
        catch
          case NonFatal(_) =>
            ()

        classPath.list(packageName).packages.foreach(pkg => scanPackage(pkg.name))

    def scanOwner(owner: ClassSymbol): Unit =
      if seenOwners.add(owner) then
        try
          owner.ensureCompleted()
          for member <- owner.info.decls do
            if member.is(Method) then
              seenMethods += member.asTerm

            if member.is(Module) then
              val moduleClass = member.moduleClass
              if moduleClass.exists then scanOwner(moduleClass.asClass)
            else if member.isClass && !member.is(PackageClass) then
              scanOwner(member.asClass)
        catch
          case NonFatal(_) =>
            ()

    packageRoots.foreach(scanPackage)
    seenMethods.toList

  private def entryPointsIRPath(className: ClassName): String =
    s"${className.nameString.split('.').iterator.filter(_.nonEmpty).mkString("/")}.sjsir"

  private def serializeClassDef(classDef: js.ClassDef): Array[Byte] =
    val output = new ByteArrayOutputStream()
    try ir.Serializers.serialize(output, ir.Hashers.hashClassDef(classDef))
    finally output.close()
    output.toByteArray

  private def generatedPackageName(packageName: String): String =
    if packageName.isEmpty then "dotty.tools.dotc.sjsmacros.generated._root_"
    else s"dotty.tools.dotc.sjsmacros.generated.$packageName"

  private def generatedClassName(packageName: String, simpleName: String): ClassName =
    ClassName(s"${generatedPackageName(packageName)}.$simpleName")

  private def macroEntryPointsClassDefsInPackage(packageName: String)(using Context): List[js.ClassDef] =
    val entries = macroEntryPointsInPackage(packageName)
    if entries.isEmpty then Nil
    else
      val moduleClassName = generatedClassName(packageName, "MacroEntryPoints$")
      implicit val pos: Position = Position.NoPosition
      List(macroEntryPointsModuleClassDef(packageName, moduleClassName, entries))

  private def macroEntryPointsModuleClassDef(packageName: String, moduleClassName: ClassName, entries: List[MacroEntryPoint])(using Context, Position): js.ClassDef =
    val moduleType = jstpe.ClassType(moduleClassName, nullable = true)
    val moduleTypeNonNull = jstpe.ClassType(moduleClassName, nullable = false)
    val writeReplaceName = js.MethodIdent(MethodName("writeReplace", Nil, jswkn.ObjectRef))
    val moduleSerializationProxyClass = encodeClassName(defn.ModuleSerializationProxyClass)
    val writeReplaceCtor = js.MethodIdent(MethodName.constructor(List(jstpe.ClassRef(jswkn.ClassClass))))
    val ctorMethod = js.MethodIdent(jswkn.NoArgConstructorName)
    val thisRef = js.This()(moduleTypeNonNull)

    js.ClassDef(
      js.ClassIdent(moduleClassName),
      NoOriginalName,
      ClassKind.ModuleClass,
      None,
      Some(js.ClassIdent(jswkn.ObjectClass)),
      Nil,
      None,
      None,
      Nil,
      List(
        js.MethodDef(
          js.MemberFlags.empty.withNamespace(js.MemberNamespace.Constructor),
          ctorMethod,
          NoOriginalName,
          Nil,
          jstpe.VoidType,
          Some(
            js.Block(
              List(
                js.ApplyStatically(
                  js.ApplyFlags.empty.withConstructor(true),
                  thisRef,
                  jswkn.ObjectClass,
                  js.MethodIdent(jswkn.NoArgConstructorName),
                  Nil,
                )(jstpe.VoidType),
                js.StoreModule(),
              )
            )
          ),
        )(js.OptimizerHints.empty, Unversioned),
        js.MethodDef(
          js.MemberFlags.empty.withNamespace(js.MemberNamespace.Private),
          writeReplaceName,
          NoOriginalName,
          Nil,
          jstpe.AnyType,
          Some(
            js.New(
              moduleSerializationProxyClass,
              writeReplaceCtor,
              List(js.ClassOf(jstpe.ClassRef(moduleClassName))),
            )
          ),
        )(js.OptimizerHints.empty, Unversioned),
      ),
      None,
      Nil,
      Nil,
      List(macroEntryPointsTopLevelExport(packageName, entries)),
    )(js.OptimizerHints.empty)

  private def macroEntryPointsTopLevelExport(packageName: String, entries: List[MacroEntryPoint])(using Context, Position): js.TopLevelExportDef =
    val exportName = MacroRuntimeExports.entryPointsExportName(packageName)
    val exportBody = js.JSArrayConstr(entries.map(exportedEntryPointValue))
    val methodDef = js.JSMethodDef(
      js.MemberFlags.empty.withNamespace(js.MemberNamespace.PublicStatic),
      js.StringLiteral(exportName),
      Nil,
      None,
      exportBody,
    )(js.OptimizerHints.empty, Unversioned)
    js.TopLevelMethodExportDef(jswkn.DefaultModuleID, methodDef)

  private def exportedEntryPointValue(entry: MacroEntryPoint)(using Context, Position): js.Tree =
    js.JSArrayConstr(
      List(
        js.StringLiteral(entry.id),
        exportedEntryPointFunction(entry),
      )
    )

  private def exportedEntryPointFunction(entry: MacroEntryPoint)(using Context, Position): js.Tree =
    val argsParam = js.ParamDef(js.LocalIdent(LocalName("args")), NoOriginalName, jstpe.AnyType, mutable = false)

    def argAt(paramIndex: Int, targetType: jstpe.Type): js.Tree =
      js.AsInstanceOf(
        js.JSSelect(argsParam.ref, js.StringLiteral(paramIndex.toString)),
        targetType,
      )

    val applyArgs =
      entry.implementationParamTypes.iterator.zipWithIndex.map { (targetType, paramIndex) =>
        argAt(paramIndex, targetType)
      }.toList

    js.Closure(
      js.ClosureFlags.arrow,
      Nil,
      List(argsParam),
      None,
      jstpe.AnyType,
      js.Apply(
        js.ApplyFlags.empty,
        js.LoadModule(entry.implementationOwnerClassName),
        entry.implementationMethod,
        applyArgs,
      )(entry.implementationResultType),
      Nil,
    )

  private def irTypesOfMethodParams(method: js.MethodIdent): IArray[jstpe.Type] =
    IArray.from(method.name.paramTypeRefs.iterator.map(irTypeOfTypeRef))

  private def irTypeOfMethodResult(method: js.MethodIdent): jstpe.Type =
    irTypeOfTypeRef(method.name.resultTypeRef)

  private def methodIdentOf(symbol: TermSymbol)(using Context): js.MethodIdent =
    given Position = Position.NoPosition
    val paramTypeRefs =
      symbol.info.paramInfoss.flatten.map { paramType =>
        toParamOrResultTypeRef(toTypeRef(fullErasure(paramType)))
      }
    val resultTypeRef =
      if symbol.isClassConstructor then jstpe.VoidRef
      else toParamOrResultTypeRef(toTypeRef(fullErasure(symbol.info.finalResultType)))
    val methodName =
      if symbol.isClassConstructor then MethodName.constructor(paramTypeRefs)
      else MethodName(SimpleMethodName(symbol.name.mangledString), paramTypeRefs, resultTypeRef)
    js.MethodIdent(methodName)

  private def irTypeOfTypeRef(typeRef: jstpe.TypeRef): jstpe.Type = typeRef match
    case jstpe.PrimRef(irTpe) =>
      irTpe
    case jstpe.ClassRef(className) if className == jswkn.ObjectClass =>
      jstpe.AnyType
    case jstpe.ClassRef(className) =>
      jstpe.ClassType(className, nullable = true)
    case arrayTypeRef: jstpe.ArrayTypeRef =>
      jstpe.ArrayType(arrayTypeRef, nullable = true)
    case transientTypeRef: jstpe.TransientTypeRef =>
      transientTypeRef.tpe
