package com.eddysystems.eddy;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.scope.BaseScopeProcessor;
import com.intellij.psi.scope.ElementClassHint;
import com.intellij.psi.scope.JavaScopeProcessorEvent;
import com.intellij.psi.scope.util.PsiScopesUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.SmartList;
import org.apache.commons.lang.NotImplementedException;
import org.apache.log4j.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import scala.collection.JavaConversions;
import tarski.Environment.Env;
import tarski.Items.*;
import tarski.Types.*;
import tarski.Tarski;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts information about the environment at a given place in the code and makes it available in a format understood by tarski
 */
public class EnvironmentProcessor extends BaseScopeProcessor implements ElementClassHint {

  private final @NotNull
  Project project;

  private final @NotNull
  Logger logger = Logger.getInstance(getClass());

  public class ShadowElement<E> {
    public final E e;
    public final int shadowingPriority;

    public ShadowElement(E e, int p) {
      this.e = e;
      shadowingPriority = p;
    }
  }

  // a cache containing all the items in the global environment (everything outside this file)
  // if the PSI referenced here changes, this map becomes useless (we can check with PsiElement.isValid())
  static final Object global_envitems_lock = new Object();
  static boolean global_envitems_ready = false;
  static Map<PsiElement, Item> global_envitems = null;

  // things that are in scope (not all these are accessible! things may be private, or not static while we are)
  private final List<ShadowElement<PsiPackage>> packages = new SmartList<ShadowElement<PsiPackage>>();
  private final List<ShadowElement<PsiClass>> classes = new SmartList<ShadowElement<PsiClass>>();
  private final List<ShadowElement<PsiVariable>> variables = new SmartList<ShadowElement<PsiVariable>>();
  private final List<ShadowElement<PsiMethod>> methods = new SmartList<ShadowElement<PsiMethod>>();

  private final PsiElement place;

  // used during walking
  private int currentLevel = 0;
  private boolean inStaticScope = false;
  private PsiElement currentFileContext;
  private boolean honorPrivate;

  public EnvironmentProcessor(@NotNull Project project, PsiElement place, boolean honorPrivate) {
    this.project = project;
    this.place = place;
    this.honorPrivate = honorPrivate;

    // this is set to null when we go to java.lang
    this.currentFileContext = place;

    logger.setLevel(Level.INFO);

    PsiScopesUtil.treeWalkUp(this, place, null);

    // TODO: add all installed packages
    // TODO: find import statements and add those to scope
  }

  private @Nullable PsiPackage getPackage(@NotNull PsiJavaFile file) {
    PsiPackage pkg = JavaPsiFacade.getInstance(project).findPackage(file.getPackageName());
    return pkg;
  }

  private PsiElement containing(PsiElement cls) {
    PsiElement parent = cls.getParent();
    if (parent instanceof PsiJavaFile) {
      return getPackage((PsiJavaFile) parent);
    } else if (parent instanceof PsiClass) {
      return parent;
    } else if (parent instanceof PsiDeclarationStatement) {
      while (!(parent instanceof PsiMethod)) {
        logger.debug("walking up to find containing method for local class " + cls + ": " + parent);
        parent = parent.getParent();
      }
      return parent;
    }
    throw new RuntimeException("unexpected container of " + cls + ": " + parent);
  }

  private Item addContainerToEnvMap(Map<PsiElement, Item> envitems, PsiElement elem) {
    // local classes
    if (elem instanceof PsiMethod)
      return addMethodToEnvMap(envitems, (PsiMethod)elem);
    if (elem instanceof PsiClass)
      return addClassToEnvMap(envitems, (PsiClass)elem);
    else if (elem instanceof PsiPackage) {
      PsiPackage pkg = (PsiPackage)elem;
      if (!envitems.containsKey(pkg)) {
        PackageItem pitem = new PackageItem(pkg.getName(), qualifiedName(pkg));
        envitems.put(pkg, pitem);
        return pitem;
      } else
        return envitems.get(pkg);
    }
    return null;
  }

  private TypeParamItem addTypeParameterToEnvMap(Map<PsiElement,Item> envitems, PsiTypeParameter p) {
    if (envitems.containsKey(p))
      return (TypeParamItem)envitems.get(p);

    PsiClassType[] extended = p.getExtendsList().getReferencedTypes();
    List<ClassType> etypes = new SmartList<ClassType>();
    for (PsiClassType e : extended) {
      etypes.add((ClassType)convertType(envitems, e));
    }
    for (int i = 1; i < extended.length; ++i) {
      assert !extended[i].resolve().isInterface();
    }

    TypeParamItem ti;

    if (etypes.isEmpty())
      ti = new TypeParamItem(p.getName(), ObjectType$.MODULE$, JavaConversions.asScalaBuffer(etypes).toList());
    else
      ti = new TypeParamItem(p.getName(), etypes.get(0), JavaConversions.asScalaBuffer(etypes.subList(1,etypes.size())).toList());

    envitems.put(p, ti);
    return ti;
  }

  private TypeItem addClassToEnvMap(Map<PsiElement,Item> envitems, PsiClass cls) {
    if (envitems.containsKey(cls))
      return (TypeItem)envitems.get(cls);

    // java.lang.Object is special
    if (cls.getQualifiedName() != null && cls.getQualifiedName().equals("java.lang.Object")) {
      envitems.put(cls, ObjectItem$.MODULE$);
      return ObjectItem$.MODULE$;
    }

    // Base
    // TODO: Handle generics
    PsiClass scls = cls.getSuperClass();
    assert scls != null;
    ClassType base = (ClassType)tarski.Tarski.toType(addClassToEnvMap(envitems, scls));

    // Type parameters
    // TODO: Handle generics
    ArrayList<TypeParamItem> j_params = new ArrayList<TypeParamItem>();
    scala.collection.immutable.List<TypeParamItem> params = JavaConversions.asScalaBuffer(j_params).toList();

    // Interfaces
    ArrayList<ClassType> j_interfaces = new ArrayList<ClassType>();
    for (PsiClass i : cls.getInterfaces()) {
      // TODO: Handle generics
      j_interfaces.add(((ClassItem)addClassToEnvMap(envitems,i)).simple());
    }
    scala.collection.immutable.List<ClassType> interfaces = JavaConversions.asScalaBuffer(j_interfaces).toList();

    PsiElement celem = containing(cls);
    ParentItem container = celem != null ? (ParentItem)addContainerToEnvMap(envitems,celem) : Tarski.localPkg();
    TypeItem item = cls.isInterface() ? new NormalInterfaceItem(cls.getName(),container,params,interfaces)
                  : cls.isEnum()      ? new EnumItem(cls.getName(),container,interfaces)
                                      : new NormalClassItem(cls.getName(),container,params,base,interfaces);
    envitems.put(cls,item);
    return item;
  }

  private CallableItem addMethodToEnvMap(Map<PsiElement,Item> envitems, PsiMethod method) {
    // get type parameters
    List<TypeParamItem> jtparams = new ArrayList<TypeParamItem>();
    for (PsiTypeParameter tp : method.getTypeParameters()) {
      jtparams.add(addTypeParameterToEnvMap(envitems, tp));
    }
    scala.collection.immutable.List<TypeParamItem> tparams = scala.collection.JavaConversions.asScalaBuffer(jtparams).toList();

    // get argument types
    List<Type> params = new SmartList<Type>();
    for (PsiParameter p : method.getParameterList().getParameters())
      params.add(convertType(envitems,p.getType()));

    // get class
    PsiClass cls = method.getContainingClass();
    assert cls != null;
    if (!envitems.containsKey(cls)) {
      addClassToEnvMap(envitems, cls);
    }
    ClassItem clsitem = (ClassItem)envitems.get(cls);

    CallableItem mitem;
    if (method.isConstructor()) {
      // TODO: varargs
      // TODO: get type parameters
      // TODO: what to do with parameters depending on type parameters and bounded types and such?
      mitem = new ConstructorItem(clsitem, tparams, scala.collection.JavaConversions.asScalaBuffer(params).toList());
    } else {
      // TODO: varargs
      // TODO: get type parameters
      // TODO: what to do with parameters depending on type parameters and bounded types and such?
      Type rtype = convertType(envitems, method.getReturnType());

      if (method.hasModifierProperty(PsiModifier.STATIC))
        mitem = new StaticMethodItem(method.getName(), clsitem, tparams, rtype, scala.collection.JavaConversions.asScalaBuffer(params).toList());
      else
        mitem = new MethodItem(method.getName(), clsitem, tparams, rtype, scala.collection.JavaConversions.asScalaBuffer(params).toList());
    }

    envitems.put(method, mitem);
    return mitem;
  }

  private Type convertType(Map<PsiElement, Item> envitems, PsiType t) {
    // TODO: Handle modifiers
    if (t instanceof PsiArrayType)
      return new ArrayType(convertType(envitems,((PsiArrayType)t).getComponentType()));

    // classes are not types in IntelliJ's version of the world, so we have to look up this class in envitems
    if (t instanceof PsiClassType) {
      PsiClass tcls = ((PsiClassType)t).resolve();
      if (tcls == null) {
        // ClassType cannot be resolved to a Class (probably because JDK is missing or the code is incomplete
        String name = ((PsiClassType)t).getClassName();
        throw new RuntimeException("Cannot resolve " + t);
      } else if (tcls instanceof PsiTypeParameter) {
        return new ParamType(addTypeParameterToEnvMap(envitems, (PsiTypeParameter)tcls));
      } else if (tcls.isInterface()) {
        // TODO: Handle generics
        return ((ClassItem)addClassToEnvMap(envitems,tcls)).simple();
      } else {
        // TODO: Handle generics
        return tarski.Tarski.toType(addClassToEnvMap(envitems,tcls));
      }
    }
    if (t == PsiType.BOOLEAN) return BooleanType$.MODULE$;
    if (t == PsiType.INT)     return IntType$.MODULE$;
    if (t == PsiType.BYTE)    return ByteType$.MODULE$;
    if (t == PsiType.CHAR)    return CharType$.MODULE$;
    if (t == PsiType.FLOAT)   return FloatType$.MODULE$;
    if (t == PsiType.DOUBLE)  return DoubleType$.MODULE$;
    if (t == PsiType.LONG)    return LongType$.MODULE$;
    if (t == PsiType.SHORT)   return ShortType$.MODULE$;
    if (t == PsiType.VOID)    return VoidType$.MODULE$;
    throw new RuntimeException("Unknown type: " + t.getCanonicalText());
  }

  private Value addFieldToEnvMap(Map<PsiElement, Item> envitems, PsiField f) {
    if (envitems.containsKey(f))
      return (Value)envitems.get(f);

    PsiClass cls = f.getContainingClass();
    assert cls != null;
    Type t = convertType(envitems,f.getType());
    ClassItem c = (ClassItem)envitems.get(cls);
    Value v =               f instanceof PsiEnumConstant ? new EnumConstantItem(f.getName(),(EnumItem)c) :
              (f.hasModifierProperty(PsiModifier.STATIC) ? new StaticFieldItem(f.getName(),t,c)
                                                         : new FieldItem(f.getName(),t,c));
    envitems.put(f, v);
    return v;
  }

  private Map<PsiElement, Item> getGlobalEnvItems() {
    if (global_envitems_ready) {
      HashMap<PsiElement, Item> newmap = new HashMap<PsiElement, Item>();
      newmap.putAll(global_envitems);
      return newmap;
    } else
      return new HashMap<PsiElement, Item>();
  }

  private void updateGlobalEnvItems(Map<PsiElement, Item> envitems) {
    // only the first one to call this function gets through
    boolean first = false;
    synchronized (global_envitems_lock) {
      if (global_envitems == null) {
        global_envitems = new HashMap<PsiElement, Item>();
        first = true;
      }
    }

    // we are the ones to update
    if (first) {
      // update global_envitems from envitems
      for (PsiElement elem: envitems.keySet()) {
        if (elem.getContainingFile() != place.getContainingFile())
          global_envitems.put(elem, envitems.get(elem));
      }

      global_envitems_ready = true;
    }
  }

  /**
   * Make the IntelliJ-independent class that is used by the tarksi engine to look up possible names
   */
  public Env getJavaEnvironment() {
    Map<PsiElement, Item> envitems = getGlobalEnvItems();
    Map<Item, Integer> localItems = new HashMap<Item, Integer>();

    // register locally visible items (each item will register things it contains, inherits from, etc.)

    for (ShadowElement<PsiPackage> spkg : packages) {
      final PsiPackage pkg = spkg.e;
      Item ipkg = addContainerToEnvMap(envitems, pkg);

      localItems.put(ipkg,spkg.shadowingPriority);

      // TODO: register everything that is below this package (some of which may already be in the env map)
    }

    // then, register classes (we need those as containing elements in the other things)
    // classes may be contained in classes, so partial-order the list first
    for (ShadowElement<PsiClass> scls : classes) {
      final PsiClass cls = scls.e;
        // TODO: get type parameters etc
      Item icls = addClassToEnvMap(envitems, cls);
      localItems.put(icls,scls.shadowingPriority);

      // if these items don't already exist, they can never shadow things
      for (PsiField f : cls.getFields()) {
        addFieldToEnvMap(envitems, f);
      }
      for (PsiMethod m : cls.getMethods()) {
        if (!envitems.containsKey(m))
          addMethodToEnvMap(envitems, m);
      }
      for (PsiMethod m : cls.getConstructors()) {
        if (!envitems.containsKey(m))
          addMethodToEnvMap(envitems, m);
      }
      for (PsiClass c : cls.getInnerClasses()) {
        if (!envitems.containsKey(c))
          addClassToEnvMap(envitems, c);
      }
    }

    // register methods (also register types used in this method)
    for (ShadowElement<PsiMethod> smethod : methods) {
      final PsiMethod method = smethod.e;
      Item imethod = addMethodToEnvMap(envitems,method);
      localItems.put(imethod,smethod.shadowingPriority);

      // TODO: local classes may go here
    }

    // then, register objects which have types (enum constants, variables, parameters, fields), and their types
    for (ShadowElement<PsiVariable> svar : variables) {
      final PsiVariable var = svar.e;
      if (var instanceof PsiField) {
        Item ivar = addFieldToEnvMap(envitems,(PsiField)var);
        localItems.put(ivar,svar.shadowingPriority);
      } else {
        assert !envitems.containsKey(var);
        Type t = convertType(envitems,var.getType());
        Item i = var instanceof PsiParameter     ? new ParameterItem(var.getName(),t)
                    : var instanceof PsiLocalVariable ? new LocalVariableItem(var.getName(),t)
                    : null;
        if (i == null)
          throw new NotImplementedException("Unknown variable: " + var);

        // actually add to envitems map
        envitems.put(var, i);
        localItems.put(i,svar.shadowingPriority);
      }
    }

    // update the global map if needed
    updateGlobalEnvItems(envitems);

    List<Item> items = new ArrayList<Item>(envitems.values());

    // find out which element we are inside (method, class or interface, or package)
    PlaceItem placeItem = null;
    boolean inside_continuable = false;
    boolean inside_breakable = false;
    List<String> labels = new SmartList<String>();
    // walk straight up until we see a method, class, or package
    PsiElement place = this.place;
    while (place != null) {
      // scan the current method for labels, loops, and switch statements
      if (placeItem != null) {
        if (place instanceof PsiLabeledStatement) {
          // found a label
          logger.info("found a labeled statement: " + place + ", label: " + ((PsiLabeledStatement) place).getLabelIdentifier());
          labels.add(((PsiLabeledStatement) place).getLabelIdentifier().getText());
        }

        if (place instanceof PsiSwitchStatement) {
          logger.info("inside switch statement: " + place);
          inside_breakable = true;
        }
        if (place instanceof PsiLoopStatement) {
          logger.info("inside loop statement: " + place);
          inside_breakable = true;
          inside_continuable = true;
        }
      }

      // add special "this" items this for each class we're inside of, with same shadowing priority as the class itself
      if (place instanceof PsiClass && !((PsiClass) place).isInterface()) { // don't make this for interfaces
        assert envitems.containsKey(place);
        ClassItem c = (ClassItem)envitems.get(place);
        assert localItems.containsKey(c);
        int p = localItems.get(c);
        ThisItem ti = new ThisItem(c);
        items.add(ti);
        localItems.put(ti,p);
      }

      if (place instanceof PsiMethod || place instanceof PsiClass || place instanceof PsiPackage) {
        assert envitems.containsKey(place);
        if (placeItem == null)
          placeItem = (PlaceItem)envitems.get(place);
      } else if (place instanceof PsiJavaFile) {
        PsiPackage pkg = getPackage((PsiJavaFile)place);
        if (pkg == null) {
          // probably we're top-level in a file without package statement, use LocalPackageItem
          if (placeItem == null)
            placeItem = Tarski.localPkg();
        } else {
          assert envitems.containsKey(pkg);
          if (placeItem == null)
            placeItem = (PlaceItem)envitems.get(pkg);
        }
        break;
      }
      place = place.getParent();
    }
    assert placeItem != null;

    logger.debug("environment taken inside " + placeItem + ": ");

    /*
    for (NamedItem item: localItems.keySet()) {
      if (item.qualifiedName().startsWith("java.lang."))
        continue;
      logger.debug("  " + item);
    }

    for (NamedItem item : items) {
      logger.debug("  " + item + (localItems.containsKey(item) ? " scope level " + localItems.get(item).toString() : " not in scope."));
    }
    */

    return Tarski.environment(items, localItems, placeItem, inside_breakable, inside_continuable, labels);
  }

  private String qualifiedName(PsiElement elem) {
    if (elem instanceof PsiQualifiedNamedElement)
      return ((PsiQualifiedNamedElement) elem).getQualifiedName();
    else if (elem instanceof PsiMethod) {
      PsiClass cls = ((PsiMethod) elem).getContainingClass();
      assert cls != null;
      return cls.getQualifiedName() + '.' + ((PsiMethod) elem).getName();
    } else if (elem instanceof PsiEnumConstant) {
      PsiClass cls = ((PsiEnumConstant) elem).getContainingClass();
      assert cls != null;
      return cls.getQualifiedName() + '.' + ((PsiEnumConstant) elem).getName();
    } else if (elem instanceof PsiField) {
      PsiClass cls = ((PsiField) elem).getContainingClass();
      assert cls != null;
      return cls.getQualifiedName() + '.' + ((PsiField) elem).getName();
    }

    logger.error("Can't compute qualified name of " + elem);
    return null;
  }

  /**
   * Check if a thing (member or class) is private or protected and therefore not accessible
   * @param element The thing to check whether it's inaccessible because it may be private or protected
   * @param containingClass The class containing the thing
   * @return whether the element is private or protected and not accessible because of it
   */
  private boolean isInaccessible(PsiModifierListOwner element, PsiClass containingClass) {
    if (element.hasModifierProperty(PsiModifier.PRIVATE)) {
      // if the member is private we can only see it if place is contained in a class in which member is declared.
      PsiClass containingPlaceClass = PsiTreeUtil.getParentOfType(place, PsiClass.class, false);
      while (containingPlaceClass != null) {
        if (containingClass == containingPlaceClass) {
          break;
        }
        containingPlaceClass = PsiTreeUtil.getParentOfType(containingPlaceClass, PsiClass.class);
      }
      if (containingPlaceClass == null) {
        return true;
      }
    }

    if (element.hasModifierProperty(PsiModifier.PROTECTED)) {
      // if the member is protected we can only see it if place is contained in a subclass of the containingClass
      PsiClass containingPlaceClass = PsiTreeUtil.getParentOfType(place, PsiClass.class, false);
      while (containingPlaceClass != null) {
        if (containingPlaceClass == containingClass)
          break;
        containingPlaceClass = containingPlaceClass.getSuperClass();
      }
      if (containingPlaceClass == null) {
        return true;
      }
    }

    return false;
  }

  @Override
  public boolean shouldProcess(DeclarationKind kind) {
    return
      kind == DeclarationKind.CLASS ||
      kind == DeclarationKind.FIELD ||
      kind == DeclarationKind.METHOD ||
      kind == DeclarationKind.VARIABLE ||
      kind == DeclarationKind.PACKAGE ||
      kind == DeclarationKind.ENUM_CONST;
  }

  @Override
  public boolean execute(@NotNull PsiElement element, ResolveState state) {

    // if we are in static scope, a class member has to be declared static for us to see it
    if (element instanceof PsiField || element instanceof PsiMethod) {
      if (inStaticScope && !((PsiMember)element).hasModifierProperty(PsiModifier.STATIC))
        return true;
    }

    if (honorPrivate) {
      PsiClass containing = null;
      if (element instanceof PsiMember)
        containing = ((PsiMember)element).getContainingClass();

      if (containing != null && isInaccessible((PsiModifierListOwner)element, containing)) {
        return true;
      }
    }

    logger.debug("found element " + element + " at level " + currentLevel);

    if (element instanceof PsiClass) {
      classes.add(new ShadowElement<PsiClass>((PsiClass)element, currentLevel));
    } else if (element instanceof PsiVariable) {
      variables.add(new ShadowElement<PsiVariable>((PsiVariable)element, currentLevel));
    } else if (element instanceof PsiMethod) {
      methods.add(new ShadowElement<PsiMethod>((PsiMethod)element, currentLevel));
    } else if (element instanceof PsiPackage) {
      packages.add(new ShadowElement<PsiPackage>((PsiPackage)element, currentLevel));
    }
    return true;
  }

  @Override
  public final void handleEvent(@NotNull Event event, Object associated){
    if (event == JavaScopeProcessorEvent.START_STATIC) {
      logger.debug("starting static scope");
      inStaticScope = true;
    } else if (event == JavaScopeProcessorEvent.SET_CURRENT_FILE_CONTEXT) {
      currentFileContext = (PsiElement)associated;
      logger.debug("switching file context: " + currentFileContext);
    } else if (event == JavaScopeProcessorEvent.CHANGE_LEVEL) {
      currentLevel++;
      logger.debug("change level to " + currentLevel);
    }
  }
}
