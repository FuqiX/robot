package org.obolibrary.robot;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Workbook;
import org.obolibrary.robot.export.*;
import org.obolibrary.robot.providers.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.search.EntitySearcher;
import org.semanticweb.owlapi.util.*;

/** @author <a href="mailto:rbca.jackson@gmail.com">Becky Jackson</a> */
public class ExportOperation {

  /** Namespace for error messages. */
  private static final String NS = "export#";

  private static final String invalidColumnError =
      NS + "INVALID COLUMN ERROR unable to find property for column header '%s'";

  private static final String includeNothingError =
      NS + "INCLUDE NOTHING ERROR you must include some types of ontology terms";

  private static final String unknownFormatError =
      NS + "UNKNOWN FORMAT ERROR '%s' is an unknown export format";

  private static final String unknownTagError =
      NS + "UNKNOWN TAG ERROR Column '%s' contains an unknown rendering tag: %s";

  private static final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();

  private static final EmptyShortFormProvider emptyProvider = new EmptyShortFormProvider();
  private static final EmptyIRIShortFormProvider emptyIRIProvider = new EmptyIRIShortFormProvider();

  // All synonym property IRIs
  private static final List<String> synonymProperties =
      Arrays.asList(
          "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
          "http://www.geneontology.org/formats/oboInOwl#hasBroadSynonym",
          "http://www.geneontology.org/formats/oboInOwl#hasNarrowSynonym",
          "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym",
          "http://purl.obolibrary.org/obo/IAO_0000118");

  /**
   * Return a map from option name to default option value, for all the available export options.
   *
   * @return a map with default values for all available options
   */
  public static Map<String, String> getDefaultOptions() {
    Map<String, String> options = new HashMap<>();
    options.put("include", "classes individuals");
    options.put("exclude-anonymous", "false");
    options.put("format", "tsv");
    options.put("sort", null);
    options.put("split", "|");
    options.put("entity-format", "NAME");
    return options;
  }

  /**
   * Given an ontology, an ioHelper, a list of columns, an output export file, and a map of options,
   * export details about the entities in the ontology to the export file. Use the columns to
   * determine which details are included in the output.
   *
   * @param ontology OWLOntology to export to table
   * @param ioHelper IOHelper to handle labels
   * @param columnNames List of column names, in order
   * @param options Map of Export options
   * @throws Exception if file does not exist to write to or a column is not a valid property
   */
  public static Table createExportTable(
      OWLOntology ontology,
      IOHelper ioHelper,
      List<String> columnNames,
      Map<String, String> options)
      throws Exception {
    boolean excludeAnonymous = OptionsHelper.optionIsTrue(options, "exclude-anonymous");

    // Get column or columns to sort on
    // If not provided, use the first column
    String sortColumn = OptionsHelper.getOption(options, "sort", columnNames.get(0));
    if (sortColumn == null) {
      sortColumn = columnNames.get(0);
    }

    // Create table object
    String format = OptionsHelper.getOption(options, "format", "tsv").toLowerCase();
    Table table = new Table(format);

    // Get a label map of all labels -> IRIs
    Map<String, IRI> labelMap = OntologyHelper.getLabelIRIs(ontology);

    // Create a QuotedEntityChecker to handle names
    QuotedEntityChecker checker = new QuotedEntityChecker();
    checker.setIOHelper(ioHelper);
    checker.addProvider(new SimpleShortFormProvider());
    checker.addProperty(dataFactory.getRDFSLabel());
    checker.addAll(ontology);

    // Default tag for entity rendering when not specified
    String entityFormat = OptionsHelper.getOption(options, "entity-format", "NAME");

    // Create some providers for rendering entities
    ShortFormProvider oboProvider = new CURIEShortFormProvider(ioHelper.getPrefixes());
    ShortFormProvider iriProvider = new IRIValueShortFormProvider();
    ShortFormProvider quotedProvider =
        new QuotedAnnotationValueShortFormProvider(
            ontology.getOWLOntologyManager(),
            oboProvider,
            ioHelper.getPrefixManager(),
            Collections.singletonList(OWLManager.getOWLDataFactory().getRDFSLabel()),
            Collections.emptyMap());
    ShortFormProvider labelProvider =
        new AnnotationValueShortFormProvider(
            ontology.getOWLOntologyManager(),
            emptyProvider,
            emptyIRIProvider,
            Collections.singletonList(OWLManager.getOWLDataFactory().getRDFSLabel()),
            Collections.emptyMap());

    // Create the Column objects and add to table
    List<String> sorts = Arrays.asList(sortColumn.trim().split("\\|"));
    for (String c : columnNames) {
      String tag = entityFormat;
      String colName = c;
      // Determine if this has a tag for rendering
      Matcher m = Pattern.compile("(.+) \\[(ID|id|IRI|iri|LABEL|label|NAME|name)]").matcher(c);
      if (m.find()) {
        colName = m.group(1);
        tag = m.group(2);
      }

      // Add some other defaults to the label map
      updateLabelMap(labelMap);

      // Try to resolve a CURIE or a label
      IRI iri = ioHelper.createIRI(colName);
      if (iri == null) {
        iri = labelMap.getOrDefault(colName, null);
      }

      // Handle the default column rendering
      if (c.equalsIgnoreCase("ID") || c.equalsIgnoreCase("CURIE")) {
        tag = "ID";
      } else if (c.equalsIgnoreCase("IRI")) {
        tag = "IRI";
      } else if (c.equalsIgnoreCase("LABEL")) {
        tag = "LABEL";
      }

      // Maybe get a property
      OWLAnnotationProperty ap = checker.getOWLAnnotationProperty(colName, false);

      // Handle some defaults
      IRI colIRI = ioHelper.createIRI(colName);
      if (colIRI != null
          && colIRI.toString().equals(dataFactory.getRDFSLabel().getIRI().toString())) {
        tag = "LABEL";
        ap = dataFactory.getRDFSLabel();
      }

      OWLDataProperty dp = checker.getOWLDataProperty(colName);
      OWLObjectProperty op = checker.getOWLObjectProperty(colName);

      // Maybe get a short form provider
      ShortFormProvider provider;
      switch (tag.toUpperCase()) {
        case "ID":
        case "CURIE":
          provider = oboProvider;
          break;
        case "IRI":
          provider = iriProvider;
          break;
        case "NAME":
          provider = quotedProvider;
          break;
        case "LABEL":
          provider = labelProvider;
          break;
        default:
          throw new Exception(String.format(unknownTagError, c, tag));
      }

      Column column;
      if (ap != null) {
        column = new Column(colName, c, ap, provider);
      } else if (dp != null) {
        column = new Column(colName, c, dp, provider);
      } else if (op != null) {
        column = new Column(colName, c, op, provider);
      } else {
        column = new Column(colName, c, iri, provider);
      }

      // Maybe set sort info
      for (String s : sorts) {
        if (c.equalsIgnoreCase(s)) {
          // Regular sort column
          column.setSort(sorts.indexOf(c), false);
          break;
        } else if (s.equalsIgnoreCase("^" + c)) {
          // Reverse sort column
          column.setSort(sorts.indexOf("^" + c), true);
          break;
        }
      }
      table.addColumn(column);
    }
    // Order the sort columns
    table.setSortColumns();

    // Get the entities to include in the spreadsheet
    Set<OWLEntity> entities = getEntities(ontology, options);

    // Get the cell values based on columns
    for (OWLEntity entity : entities) {
      table.addRow(getRow(ontology, table, entity, excludeAnonymous));
    }

    // Sort the rows by sort column or columns
    table.sortRows();
    return table;
  }

  /**
   * Save the Table object as: csv, tsv, html, json, or xlsx.
   *
   * @param table Table object to save
   * @param exportPath path to export file
   * @param options map of export options
   * @throws Exception on writing file, or if format is unknown
   */
  public static void saveTable(Table table, String exportPath, Map<String, String> options)
      throws Exception {
    String format = OptionsHelper.getOption(options, "format", "tsv").toLowerCase();
    String split = OptionsHelper.getOption(options, "split", "|");
    File exportFile = new File(exportPath);
    switch (format) {
      case "tsv":
        IOHelper.writeTable(table.toList(split), exportFile, '\t');
        break;
      case "csv":
        IOHelper.writeTable(table.toList(split), exportFile, ',');
        break;
      case "html":
        try (PrintWriter out = new PrintWriter(exportFile)) {
          out.print(table.toHTML(split));
        }
        break;
      case "json":
        try (PrintWriter out = new PrintWriter(exportFile)) {
          out.print(table.toJSON());
        }
        break;
      case "xlsx":
        try (Workbook wb = table.asWorkbook(split);
            FileOutputStream fos = new FileOutputStream(exportFile)) {
          wb.write(fos);
        }
        break;
      default:
        throw new Exception(String.format(unknownFormatError, format));
    }
  }

  /**
   * Given a label function, a collection of class expressions, and a boolean indicating to include
   * anonymous classes, return the classes as a string. Multiple values are separated by the pipe
   * character. Use labels when possible. Return null if no values exist.
   *
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param classes Set of class expressions to convert to string
   * @param excludeAnonymous if true, exclude anonymous class expressions
   * @return String of class expressions or null
   */
  private static List<String> classExpressionsToString(
      RendererType rt,
      ShortFormProvider provider,
      Collection<OWLClassExpression> classes,
      boolean excludeAnonymous) {
    List<String> strings = new ArrayList<>();
    for (OWLClassExpression expr : classes) {
      if (expr.isAnonymous() && !excludeAnonymous) {
        // Get a Manchester string using labels
        String manString = renderManchester(rt, provider, expr);
        strings.add(manString);
      } else if (!expr.isAnonymous()) {
        OWLClass sc = expr.asOWLClass();
        strings.add(renderManchester(rt, provider, sc));
      }
    }
    return strings;
  }

  /**
   * Return a Cell containing class expressions.
   *
   * @param exprs Collection of OWLClassExpressions to render in property cell
   * @param column Column for this Cell
   * @param displayRendererType RendererType for display value
   * @param sortRendererType RendererType for sort value
   * @param provider ShortFormProvider to resolve entities
   * @param excludeAnonymous if true, exclude anonymous expressions
   * @return Cell for this Column containing class expressions
   */
  private static Cell getClassCell(
      Collection<OWLClassExpression> exprs,
      Column column,
      RendererType displayRendererType,
      RendererType sortRendererType,
      ShortFormProvider provider,
      boolean excludeAnonymous) {
    List<String> displays =
        classExpressionsToString(displayRendererType, provider, exprs, excludeAnonymous);
    List<String> sorts;
    if (sortRendererType != null) {
      sorts = classExpressionsToString(sortRendererType, provider, exprs, excludeAnonymous);
    } else {
      sorts = displays;
    }
    return (new Cell(column, displays, sorts));
  }

  /**
   * Given an OWLOntology and a map of options for which types of entities to included return a set
   * of entities in the ontology.
   *
   * @param ontology OWLOntology to get entities from
   * @param options map of export options
   * @return set of OWLEntities in the ontology
   */
  private static Set<OWLEntity> getEntities(OWLOntology ontology, Map<String, String> options) {
    // Determine what types of entities to include
    boolean includeClasses = false;
    boolean includeProperties = false;
    boolean includeIndividuals = false;
    String include = OptionsHelper.getOption(options, "include");

    // Split include on space, comma, or tab
    // If 'include' doesn't have any of these characters, it is only one value
    String[] split = {include};
    if (include.contains(" ")) {
      split = include.split(" ");
    } else if (include.contains(",")) {
      split = include.split(",");
    } else if (include.contains("\t")) {
      split = include.split("\t");
    }

    for (String i : split) {
      switch (i.toLowerCase().trim()) {
        case "classes":
          includeClasses = true;
          break;
        case "properties":
          includeProperties = true;
          break;
        case "individuals":
          includeIndividuals = true;
          break;
      }
    }
    if (!includeClasses && !includeProperties && !includeIndividuals) {
      // If all three are false, nothing to include
      throw new IllegalArgumentException(includeNothingError);
    }

    Set<OWLEntity> entities = OntologyHelper.getEntities(ontology);
    // Remove defaults
    entities.remove(dataFactory.getOWLThing());
    entities.remove(dataFactory.getOWLNothing());
    entities.remove(dataFactory.getOWLTopObjectProperty());
    entities.remove(dataFactory.getOWLBottomObjectProperty());
    entities.remove(dataFactory.getOWLTopDataProperty());
    entities.remove(dataFactory.getOWLBottomDataProperty());

    // If we need to exclude anything, return a set of trimmed entities
    Set<OWLEntity> trimmedEntities = new HashSet<>();
    for (OWLEntity e : entities) {
      if (e.isOWLClass() && !includeClasses) {
        continue;
      } else if ((e.isOWLObjectProperty() || e.isOWLDataProperty() || e.isOWLAnnotationProperty())
          && !includeProperties) {
        continue;
      } else if (e.isOWLNamedIndividual() && !includeIndividuals) {
        continue;
      } else if (e.isOWLDatatype()) {
        // Always exclude datatypes
        continue;
      }
      trimmedEntities.add(e);
    }
    return trimmedEntities;
  }

  /**
   * Return a cell containing entity type, rendered based on the provider for the Column.
   *
   * @param type EntityType of the target entity
   * @param column Column for this cell
   * @return Cell for this Column containing entity type as string rendering
   */
  private static Cell getEntityTypeCell(EntityType type, Column column) {
    ShortFormProvider provider = column.getShortFormProvider();
    String cellValue;
    if (provider instanceof CURIEShortFormProvider) {
      CURIEShortFormProvider sfp = (CURIEShortFormProvider) provider;
      cellValue = sfp.getShortForm(type.getIRI());
    } else if (provider instanceof AnnotationValueShortFormProvider) {
      cellValue = type.getPrintName();
    } else {
      // IRI provider
      cellValue = type.getIRI().toString();
    }
    return new Cell(column, cellValue);
  }

  /**
   * Return a Cell containing property expressions.
   *
   * @param exprs Collection of OWLPropertyExpressions to render in property cell
   * @param column Column for this Cell
   * @param displayRendererType RendererType for display value
   * @param sortRendererType RendererType for sort value
   * @param provider ShortFormProvider to resolve entities
   * @param excludeAnonymous if true, exclude anonymous expressions
   * @return Cell for this Column containing property expressions
   */
  private static Cell getPropertyCell(
      Collection<? extends OWLPropertyExpression> exprs,
      Column column,
      RendererType displayRendererType,
      RendererType sortRendererType,
      ShortFormProvider provider,
      boolean excludeAnonymous) {
    List<String> displays =
        propertyExpressionsToString(displayRendererType, provider, exprs, excludeAnonymous);
    List<String> sorts;
    if (sortRendererType != null) {
      sorts = propertyExpressionsToString(sortRendererType, provider, exprs, excludeAnonymous);
    } else {
      sorts = displays;
    }
    return new Cell(column, displays, sorts);
  }

  /**
   * Given an OWL Ontology, an OWL Entity, and an OWL Annotation Property, get all property values
   * as one string separated by the pipe character. If there are no property values, return null.
   *
   * @param ontology OWLOntology to get values from
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param entity OWLEntity to get annotations on
   * @param ap OWLAnnotationProperty to get the value(s) of
   * @return String of values or null
   */
  private static List<String> getPropertyValues(
      OWLOntology ontology,
      RendererType rt,
      ShortFormProvider provider,
      OWLEntity entity,
      OWLAnnotationProperty ap) {
    List<String> values = new ArrayList<>();
    for (OWLAnnotationAssertionAxiom a :
        EntitySearcher.getAnnotationAssertionAxioms(entity, ontology)) {
      if (a.getProperty().getIRI() == ap.getIRI()) {
        values.add(renderManchester(rt, provider, a.getValue()));
      }
    }
    return values;
  }

  /**
   * Given an OWL Ontology, a quoted short form provider, an OWL Entity, and an OWL Data Property,
   * get all property values as one string separated by the pipe character. If there are no property
   * values, return null.
   *
   * @param ontology OWLOntology to get values from
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param entity OWLEntity to get relations of
   * @param dp OWLDataProperty to get the value(s) of
   * @param excludeAnonymous if true, do not include anonymous class expressions
   * @return String of values or null
   */
  private static List<String> getPropertyValues(
      OWLOntology ontology,
      RendererType rt,
      ShortFormProvider provider,
      OWLEntity entity,
      OWLDataProperty dp,
      boolean excludeAnonymous) {
    if (entity.isOWLNamedIndividual()) {
      OWLNamedIndividual i = entity.asOWLNamedIndividual();
      Collection<OWLLiteral> propVals = EntitySearcher.getDataPropertyValues(i, dp, ontology);
      return propVals.stream().map(OWLLiteral::toString).collect(Collectors.toList());
    } else if (entity.isOWLClass()) {
      // Find super class expressions that use this property
      List<String> vals = new ArrayList<>();
      for (OWLClassExpression expr :
          EntitySearcher.getSuperClasses(entity.asOWLClass(), ontology)) {
        if (!expr.isAnonymous()) {
          continue;
        }
        // break down into conjuncts
        vals.addAll(
            getRestrictionFillers(expr.asConjunctSet(), dp, rt, provider, excludeAnonymous));
      }
      // Find equivalent class expressions that use this property
      for (OWLClassExpression expr :
          EntitySearcher.getEquivalentClasses(entity.asOWLClass(), ontology)) {
        if (!expr.isAnonymous()) {
          continue;
        }
        // break down into conjuncts
        vals.addAll(
            getRestrictionFillers(expr.asConjunctSet(), dp, rt, provider, excludeAnonymous));
      }
      return vals;
    } else {
      return Collections.emptyList();
    }
  }

  /**
   * Given an OWL Ontology, a quoted short form provider, an OWL Entity, and an OWL Object Property,
   * get all property values as one string separated by the pipe character. Use labels when
   * possible. If there are no property values, return null.
   *
   * @param ontology OWLOntology to get values from
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param entity OWLEntity to get annotations on
   * @param op OWLObjectProperty to get the value(s) of
   * @param excludeAnonymous if true, do not include anonymous class expressions
   * @return String of values or null
   */
  private static List<String> getPropertyValues(
      OWLOntology ontology,
      RendererType rt,
      ShortFormProvider provider,
      OWLEntity entity,
      OWLObjectProperty op,
      boolean excludeAnonymous) {
    if (entity.isOWLNamedIndividual()) {
      OWLNamedIndividual i = entity.asOWLNamedIndividual();
      Collection<OWLIndividual> propVals = EntitySearcher.getObjectPropertyValues(i, op, ontology);
      return propVals
          .stream()
          .filter(OWLIndividual::isNamed)
          .map(pv -> renderManchester(rt, provider, pv.asOWLNamedIndividual()))
          .collect(Collectors.toList());
    } else if (entity.isOWLClass()) {
      // Find super class expressions that use this property
      List<String> exprs = new ArrayList<>();
      for (OWLClassExpression expr :
          EntitySearcher.getSuperClasses(entity.asOWLClass(), ontology)) {
        if (!expr.isAnonymous()) {
          continue;
        }
        // break down into conjuncts
        exprs.addAll(
            getRestrictionFillers(expr.asConjunctSet(), op, rt, provider, excludeAnonymous));
      }
      // Find equivalent class expressions that use this property
      for (OWLClassExpression expr :
          EntitySearcher.getEquivalentClasses(entity.asOWLClass(), ontology)) {
        if (!expr.isAnonymous()) {
          continue;
        }
        // break down into conjuncts
        exprs.addAll(
            getRestrictionFillers(expr.asConjunctSet(), op, rt, provider, excludeAnonymous));
      }
      return exprs;
    } else {
      return Collections.emptyList();
    }
  }

  /**
   * Given a set of OWL class expressions, a data property, a quoted short form provider, and a
   * boolean indicating to exclude anonymous expressions, get the fillers of the restrictions and
   * determine if the data property used in the original expression matches the provided data
   * property. If so, add the filler to the set.
   *
   * @param exprs set of OWLClassExpressions to check
   * @param dp OWLDataProperty to look for
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param excludeAnonymous if true, exclude anonymous data ranges
   * @return set of fillers that are 'values' of the data property
   */
  private static Set<String> getRestrictionFillers(
      Set<OWLClassExpression> exprs,
      OWLDataProperty dp,
      RendererType rt,
      ShortFormProvider provider,
      boolean excludeAnonymous) {
    Set<String> fillers = new HashSet<>();
    for (OWLClassExpression ce : exprs) {
      // Determine the type of restriction
      ClassExpressionType t = ce.getClassExpressionType();
      OWLDataPropertyExpression pe;
      OWLDataRange f;
      int n;
      switch (t) {
        case DATA_ALL_VALUES_FROM:
          OWLDataAllValuesFrom avf = (OWLDataAllValuesFrom) ce;
          f = avf.getFiller();
          pe = avf.getProperty();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLDataProperty prop = pe.asOWLDataProperty();
            if (prop.getIRI() == dp.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, null));
            }
          }
          break;
        case DATA_SOME_VALUES_FROM:
          OWLDataSomeValuesFrom svf = (OWLDataSomeValuesFrom) ce;
          f = svf.getFiller();
          pe = svf.getProperty();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLDataProperty prop = pe.asOWLDataProperty();
            if (prop.getIRI() == dp.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, null));
            }
          }
          break;
        case DATA_EXACT_CARDINALITY:
          OWLDataExactCardinality ec = (OWLDataExactCardinality) ce;
          f = ec.getFiller();
          pe = ec.getProperty();
          n = ec.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLDataProperty prop = pe.asOWLDataProperty();
            if (prop.getIRI() == dp.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
        case DATA_MIN_CARDINALITY:
          OWLDataMinCardinality minc = (OWLDataMinCardinality) ce;
          f = minc.getFiller();
          pe = minc.getProperty();
          n = minc.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLDataProperty prop = pe.asOWLDataProperty();
            if (prop.getIRI() == dp.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
        case DATA_MAX_CARDINALITY:
          OWLDataMaxCardinality maxc = (OWLDataMaxCardinality) ce;
          f = maxc.getFiller();
          pe = maxc.getProperty();
          n = maxc.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLDataProperty prop = pe.asOWLDataProperty();
            if (prop.getIRI() == dp.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
      }
    }
    return fillers;
  }

  /**
   * Given a set of OWL class expressions, an object property, a quoted short form provider, and a
   * boolean indicating to exclude anonymous expressions, get the fillers of the restrictions and
   * determine if the object property used in the original expression matches the provided object
   * property. If so, add the filler to the set.
   *
   * @param exprs set of OWLClassExpressions to check
   * @param op OWLObjectProperty to look for
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param excludeAnonymous if true, exclude anonymous class expressions
   * @return set of fillers that are 'values' of the object property
   */
  private static Set<String> getRestrictionFillers(
      Set<OWLClassExpression> exprs,
      OWLObjectProperty op,
      RendererType rt,
      ShortFormProvider provider,
      boolean excludeAnonymous) {
    Set<String> fillers = new HashSet<>();
    for (OWLClassExpression ce : exprs) {
      // Determine the type of restriction
      ClassExpressionType t = ce.getClassExpressionType();
      OWLObjectPropertyExpression pe;
      OWLClassExpression f;
      int n;
      switch (t) {
        case OBJECT_ALL_VALUES_FROM:
          // property only object
          OWLObjectAllValuesFrom avf = (OWLObjectAllValuesFrom) ce;
          pe = avf.getProperty();
          f = avf.getFiller();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLObjectProperty prop = pe.asOWLObjectProperty();
            if (prop.getIRI() == op.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, null));
            }
          }
          break;
        case OBJECT_SOME_VALUES_FROM:
          // property some object
          OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom) ce;
          pe = svf.getProperty();
          f = svf.getFiller();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLObjectProperty prop = pe.asOWLObjectProperty();
            if (prop.getIRI() == op.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, null));
            }
          }
          break;
        case OBJECT_EXACT_CARDINALITY:
          // property exactly n object
          OWLObjectExactCardinality ec = (OWLObjectExactCardinality) ce;
          pe = ec.getProperty();
          f = ec.getFiller();
          n = ec.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLObjectProperty prop = pe.asOWLObjectProperty();
            if (prop.getIRI() == op.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
        case OBJECT_MIN_CARDINALITY:
          // property min n object
          OWLObjectMinCardinality minc = (OWLObjectMinCardinality) ce;
          pe = minc.getProperty();
          f = minc.getFiller();
          n = minc.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLObjectProperty prop = pe.asOWLObjectProperty();
            if (prop.getIRI() == op.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
        case OBJECT_MAX_CARDINALITY:
          // property max n object
          OWLObjectMaxCardinality maxc = (OWLObjectMaxCardinality) ce;
          pe = maxc.getProperty();
          f = maxc.getFiller();
          n = maxc.getCardinality();
          if (excludeAnonymous && f.isAnonymous()) {
            continue;
          }
          if (!pe.isAnonymous()) {
            OWLObjectProperty prop = pe.asOWLObjectProperty();
            if (prop.getIRI() == op.getIRI()) {
              fillers.add(renderRestrictionString(rt, provider, f, n));
            }
          }
          break;
        case OBJECT_ONE_OF:
        case OBJECT_UNION_OF:
        case OBJECT_INTERSECTION_OF:
          // TODO
          break;
      }
    }

    // Not handled:
    // ClassExpressionType.OBJECT_ONE_OF
    // ClassExpressionType.OBJECT_INTERSECTION_OF
    // ClassExpressionType.OBJECT_UNION_OF
    // ClassExpressionType.OBJECT_COMPLEMENT_OF
    // ClassExpressionType.OBJECT_HAS_SELF
    // ClassExpressionType.OBJECT_HAS_VALUE

    return fillers;
  }

  /**
   * Return a Row for an OWLEntity from an ontology.
   *
   * @param ontology OWLOntology to get details from
   * @param table Table to get rendering information and columns
   * @param entity OWLEntity to get details of
   * @param excludeAnonymous if true, exclude anonymous expressions
   * @return Row object for the OWLEntity
   * @throws Exception on invalid column
   */
  private static Row getRow(
      OWLOntology ontology, Table table, OWLEntity entity, boolean excludeAnonymous)
      throws Exception {

    String format = table.getFormat();

    RendererType displayRendererType = table.getDisplayRendererType();
    RendererType sortRendererType = table.getSortRendererType();

    Row row = new Row(entity.getIRI());
    for (Column col : table.getColumns()) {

      String colName = col.getName();
      OWLProperty maybeAnnotation = col.getProperty();
      if (maybeAnnotation instanceof OWLAnnotationProperty) {
        OWLAnnotationProperty maybeLabel = (OWLAnnotationProperty) maybeAnnotation;
        if (maybeLabel.isLabel()) {
          // Handle like we do default LABEL columns
          colName = "LABEL";
        }
      }
      ShortFormProvider provider = col.getShortFormProvider();

      Cell cell;
      switch (colName) {
        case "IRI":
          String iriString = entity.getIRI().toString();
          if (format.equalsIgnoreCase("html")) {
            String display = String.format("<a href=\"%s'\">%s</a>", iriString, iriString);
            cell = new Cell(col, display, iriString);
          } else {
            cell = new Cell(col, iriString);
          }
          row.add(cell);
          continue;
        case "ID":
        case "CURIE":
          String display = renderManchester(displayRendererType, provider, entity);
          String sort = renderManchester(sortRendererType, provider, entity);
          row.add(new Cell(col, display, sort));
          continue;
        case "LABEL":
          // Display and sort will always be the same since this is a literal annotation
          String providerLabel = provider.getShortForm(entity);
          row.add(new Cell(col, providerLabel, providerLabel));
          continue;
        case "SYNONYMS":
          List<String> values = getSynonyms(ontology, entity);
          row.add(new Cell(col, values));
          continue;
        case "subclasses":
          if (entity.isOWLClass()) {
            Collection<OWLClassExpression> subclasses =
                EntitySearcher.getSubClasses(entity.asOWLClass(), ontology);
            row.add(
                getClassCell(
                    subclasses,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));
          }
          continue;
      }

      // If a property exists, use this property to get values
      OWLProperty colProperty = col.getProperty();
      if (colProperty != null) {
        if (colProperty instanceof OWLAnnotationProperty) {
          OWLAnnotationProperty ap = (OWLAnnotationProperty) colProperty;
          List<String> display =
              getPropertyValues(ontology, displayRendererType, provider, entity, ap);
          List<String> sort;
          if (sortRendererType != null) {
            sort = getPropertyValues(ontology, sortRendererType, provider, entity, ap);
          } else {
            sort = display;
          }
          row.add(new Cell(col, display, sort));
          continue;

        } else if (colProperty instanceof OWLDataProperty) {
          OWLDataProperty dp = (OWLDataProperty) colProperty;
          List<String> display =
              getPropertyValues(
                  ontology, displayRendererType, provider, entity, dp, excludeAnonymous);
          List<String> sort;
          if (sortRendererType != null) {
            sort =
                getPropertyValues(
                    ontology, sortRendererType, provider, entity, dp, excludeAnonymous);
          } else {
            sort = display;
          }
          row.add(new Cell(col, display, sort));
          continue;

        } else {
          // Object property
          OWLObjectProperty op = (OWLObjectProperty) colProperty;
          List<String> display =
              getPropertyValues(
                  ontology, displayRendererType, provider, entity, op, excludeAnonymous);
          List<String> sort;
          if (sortRendererType != null) {
            sort =
                getPropertyValues(
                    ontology, sortRendererType, provider, entity, op, excludeAnonymous);
          } else {
            sort = display;
          }
          row.add(new Cell(col, display, sort));
          continue;
        }
      }

      // Otherwise, determine what to do based on the IRI
      IRI colIRI = col.getIRI();
      // Set to IRI string or empty string
      String iriStr;
      if (colIRI != null) {
        iriStr = colIRI.toString();
      } else {
        iriStr = "";
      }

      // Check for IRI matches
      switch (iriStr) {
        case "http://www.w3.org/2000/01/rdf-schema#subClassOf":
          // SubClass Of
          if (entity.isOWLClass()) {
            Collection<OWLClassExpression> supers =
                EntitySearcher.getSuperClasses(entity.asOWLClass(), ontology);
            // owl:Thing should not be included in the subclass of column
            supers.remove(dataFactory.getOWLThing());
            row.add(
                getClassCell(
                    supers,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));
          }
          break;
        case "http://www.w3.org/2000/01/rdf-schema#subPropertyOf":
          // SubProperty Of
          if (entity.isOWLAnnotationProperty()) {
            // Annotation properties always render as labels (no expressions)
            Collection<OWLAnnotationProperty> supers =
                EntitySearcher.getSuperProperties(entity.asOWLAnnotationProperty(), ontology);
            row.add(
                getPropertyCell(
                    supers,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));

          } else if (entity.isOWLDataProperty()) {
            Collection<OWLDataPropertyExpression> supers =
                EntitySearcher.getSuperProperties(entity.asOWLDataProperty(), ontology);
            row.add(
                getPropertyCell(
                    supers,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));

          } else if (entity.isOWLObjectProperty()) {
            Collection<OWLObjectPropertyExpression> supers =
                EntitySearcher.getSuperProperties(entity.asOWLObjectProperty(), ontology);
            row.add(
                getPropertyCell(
                    supers,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));
          }
          break;
        case "http://www.w3.org/2002/07/owl#equivalentClass":
          // Equivalent Classes
          if (entity.isOWLClass()) {
            Collection<OWLClassExpression> eqs =
                EntitySearcher.getEquivalentClasses(entity.asOWLClass(), ontology);
            row.add(
                getClassCell(
                    eqs, col, displayRendererType, sortRendererType, provider, excludeAnonymous));
          }
          break;
        case "http://www.w3.org/2002/07/owl#equivalentProperty":
          // Equivalent Properties
          if (entity.isOWLAnnotationProperty()) {
            Collection<OWLAnnotationProperty> eqs =
                EntitySearcher.getEquivalentProperties(entity.asOWLAnnotationProperty(), ontology);
            row.add(
                getPropertyCell(
                    eqs, col, displayRendererType, sortRendererType, provider, excludeAnonymous));

          } else if (entity.isOWLDataProperty()) {
            Collection<OWLDataPropertyExpression> eqs =
                EntitySearcher.getEquivalentProperties(entity.asOWLDataProperty(), ontology);
            row.add(
                getPropertyCell(
                    eqs, col, displayRendererType, sortRendererType, provider, excludeAnonymous));

          } else if (entity.isOWLObjectProperty()) {
            Collection<OWLObjectPropertyExpression> eqs =
                EntitySearcher.getEquivalentProperties(entity.asOWLObjectProperty(), ontology);
            row.add(
                getPropertyCell(
                    eqs, col, displayRendererType, sortRendererType, provider, excludeAnonymous));
          }
          break;
        case "http://www.w3.org/2002/07/owl#disjointWith":
          // Disjoint Entities
          if (entity.isOWLClass()) {
            Collection<OWLClassExpression> disjoints =
                EntitySearcher.getDisjointClasses(entity.asOWLClass(), ontology);
            row.add(
                getClassCell(
                    disjoints,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));

          } else if (entity.isOWLAnnotationProperty()) {
            Collection<OWLAnnotationProperty> disjoints =
                EntitySearcher.getDisjointProperties(entity.asOWLAnnotationProperty(), ontology);
            row.add(
                getPropertyCell(
                    disjoints,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));

          } else if (entity.isOWLDataProperty()) {
            Collection<OWLDataPropertyExpression> disjoints =
                EntitySearcher.getDisjointProperties(entity.asOWLDataProperty(), ontology);
            row.add(
                getPropertyCell(
                    disjoints,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));

          } else if (entity.isOWLObjectProperty()) {
            Collection<OWLObjectPropertyExpression> disjoints =
                EntitySearcher.getDisjointProperties(entity.asOWLObjectProperty(), ontology);
            row.add(
                getPropertyCell(
                    disjoints,
                    col,
                    displayRendererType,
                    sortRendererType,
                    provider,
                    excludeAnonymous));
          }
          break;
        case "http://www.w3.org/1999/02/22-rdf-syntax-ns#type":
          // Class Assertioons
          if (entity.isOWLNamedIndividual()) {
            Collection<OWLClassExpression> types =
                EntitySearcher.getTypes(entity.asOWLNamedIndividual(), ontology);
            if (!types.isEmpty()) {
              row.add(
                  getClassCell(
                      types,
                      col,
                      displayRendererType,
                      sortRendererType,
                      provider,
                      excludeAnonymous));
            } else {
              // No class assertions, just provide the entity type
              row.add(getEntityTypeCell(entity.getEntityType(), col));
            }

          } else {
            // Not an individual, just return the entity type
            row.add(getEntityTypeCell(entity.getEntityType(), col));
          }
          break;
        default:
          throw new Exception(String.format(invalidColumnError, colName));
      }
    }

    return row;
  }

  /**
   * Get a list of the synonyms for an entity. Synonyms are one of: oboInOwl broad, narrow, related,
   * or exact synonym, or IAO alternative term.
   *
   * @param ontology OWLOntology to get annotation assertiono axioms from
   * @param entity OWLEntity to get synonyms of
   * @return list of string synonyms
   */
  private static List<String> getSynonyms(OWLOntology ontology, OWLEntity entity) {
    List<String> synonyms = new ArrayList<>();
    for (OWLAnnotationAssertionAxiom ax : ontology.getAnnotationAssertionAxioms(entity.getIRI())) {
      String apIRI = ax.getProperty().getIRI().toString();
      if (synonymProperties.contains(apIRI)) {
        OWLLiteral lit = ax.getValue().asLiteral().orNull();
        if (lit != null) {
          synonyms.add(lit.getLiteral());
        }
      }
    }
    Collections.sort(synonyms);
    return synonyms;
  }

  /**
   * Given a label function, a collection of property expressions, and a boolean indicating to
   * include anonymous expressions, return the parent properties as a string. Multiple values are
   * separated by the pipe character. Use labels when possible. Return null if no values exist.
   *
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param props Set of property expressions to convert to string
   * @param excludeAnonymous if true, exclude anonymous expressions
   * @return String of property expressions or null
   */
  private static List<String> propertyExpressionsToString(
      RendererType rt, ShortFormProvider provider, Collection<?> props, boolean excludeAnonymous) {
    // Try to convert to object property expressions
    Collection<OWLObjectPropertyExpression> opes =
        props
            .stream()
            .map(p -> (OWLPropertyExpression) p)
            .filter(OWLPropertyExpression::isObjectPropertyExpression)
            .map(p -> (OWLObjectPropertyExpression) p)
            .collect(Collectors.toList());
    // Try to convert to data property expressions
    Collection<OWLDataPropertyExpression> dpes =
        props
            .stream()
            .map(p -> (OWLPropertyExpression) p)
            .filter(OWLPropertyExpression::isDataPropertyExpression)
            .map(p -> (OWLDataPropertyExpression) p)
            .collect(Collectors.toList());

    List<String> strings = new ArrayList<>();
    // Only one of the above collections will have entries
    // Maybe process object property expressions
    for (OWLObjectPropertyExpression expr : opes) {
      if (expr.isAnonymous() && !excludeAnonymous) {
        String manString = renderManchester(rt, provider, expr);
        strings.add(manString);
      } else if (!expr.isAnonymous()) {
        OWLObjectProperty op = expr.asOWLObjectProperty();
        strings.add(renderManchester(rt, provider, op));
      }
    }
    // Maybe process data property expressions
    for (OWLDataPropertyExpression expr : dpes) {
      if (expr.isAnonymous() && !excludeAnonymous) {
        String manString = renderManchester(rt, provider, expr);
        strings.add(manString);
      } else if (!expr.isAnonymous()) {
        OWLDataProperty dp = expr.asOWLDataProperty();
        strings.add(renderManchester(rt, provider, dp));
      }
    }
    return strings;
  }

  /**
   * Render a Manchester string for an OWLObject. The RendererType will determine what Manchester
   * OWL Syntax renderer will be created.
   *
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvideer to resolve entities
   * @param object OWLObject to render
   * @return String rendering of OWLObject based on renderer type
   */
  private static String renderManchester(
      RendererType rt, ShortFormProvider provider, OWLObject object) {
    ManchesterOWLSyntaxObjectRenderer renderer;
    StringWriter sw = new StringWriter();

    if (object instanceof OWLAnnotationValue) {
      // Just return the value of literal annotations, don't render
      OWLAnnotationValue v = (OWLAnnotationValue) object;
      if (v.isLiteral()) {
        OWLLiteral lit = v.asLiteral().orNull();
        if (lit != null) {
          return lit.getLiteral();
        }
      }
    }

    if (provider instanceof QuotedAnnotationValueShortFormProvider && object.isAnonymous()) {
      // Handle quoting
      QuotedAnnotationValueShortFormProvider qavsfp =
          (QuotedAnnotationValueShortFormProvider) provider;
      qavsfp.toggleQuoting();
      if (rt == RendererType.OBJECT_HTML_RENDERER) {
        renderer = new ManchesterOWLSyntaxObjectHTMLRenderer(sw, qavsfp);
      } else {
        // Default renderer
        renderer = new ManchesterOWLSyntaxObjectRenderer(sw, qavsfp);
      }
      object.accept(renderer);
      qavsfp.toggleQuoting();
    } else {
      if (rt == RendererType.OBJECT_HTML_RENDERER) {
        renderer = new ManchesterOWLSyntaxObjectHTMLRenderer(sw, provider);
      } else {
        // Default renderer
        renderer = new ManchesterOWLSyntaxObjectRenderer(sw, provider);
      }
      object.accept(renderer);
    }
    return sw.toString().replace("\n", "").replaceAll(" +", " ");
  }

  /**
   * Render a Manchester String for a restriction. The RendererType will determine what Manchester
   * OWL Syntax renderer will be created. If an Integer is provided, this will be included as the
   * cardinality.
   *
   * @param rt RendererType to use to render Manchester
   * @param provider ShortFormProvider to resolve entities
   * @param filler OWLObject restriction filler
   * @param n Integer optional cardinality restriction
   * @return String rendering of OWLObject based on renderer type
   */
  private static String renderRestrictionString(
      RendererType rt, ShortFormProvider provider, OWLObject filler, Integer n) {
    String render = renderManchester(rt, provider, filler);
    if (filler instanceof OWLEntity && n != null) {
      // Not anonymous, has cardinality
      return String.format("%d %s", n, render);
    } else if (filler instanceof OWLEntity) {
      // Not anonymous, no cardinality
      return render;
    } else if (n != null) {
      // Expression, has cardinality
      return String.format("%d (%s)", n, render);
    } else {
      // Expression, no cardinality
      return String.format("(%s)", render);
    }
  }

  /**
   * Add some defaults to the label map.
   *
   * @param labelMap label map to update
   */
  private static void updateLabelMap(Map<String, IRI> labelMap) {
    // Uppercase with space
    labelMap.put("SubClass Of", IRI.create("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    labelMap.put(
        "SubProperty Of", IRI.create("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"));
    labelMap.put("Equivalent Class", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put("Equivalent Classes", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put(
        "Equivalent Property", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put(
        "Equivalent Properties", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put("Disjoint With", IRI.create("http://www.w3.org/2002/07/owl#disjointWith"));
    labelMap.put("Type", IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));

    // Uppercase no space
    labelMap.put("SubClassOf", IRI.create("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    labelMap.put("SubPropertyOf", IRI.create("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"));
    labelMap.put("EquivalentClass", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put("EquivalentClasses", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put(
        "EquivalentProperty", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put(
        "EquivalentProperties", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put("DisjointWith", IRI.create("http://www.w3.org/2002/07/owl#disjointWith"));

    // Camel case
    labelMap.put("subClassOf", IRI.create("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    labelMap.put("subPropertyOf", IRI.create("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"));
    labelMap.put("equivalentClass", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put("equivalentClasses", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put(
        "equivalentProperty", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put(
        "equivalentProperties", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put("disjointWith", IRI.create("http://www.w3.org/2002/07/owl#disjointWith"));

    // Lowercase with space
    labelMap.put("subclass of", IRI.create("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    labelMap.put(
        "subproperty of", IRI.create("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"));
    labelMap.put("equivalent class", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put("equivalent classes", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put(
        "equivalent property", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put(
        "equivalent properties", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put("disjoint with", IRI.create("http://www.w3.org/2002/07/owl#disjointWith"));
    labelMap.put("type", IRI.create("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"));

    // Lowercase no space
    labelMap.put("subclassof", IRI.create("http://www.w3.org/2000/01/rdf-schema#subClassOf"));
    labelMap.put("subpropertyof", IRI.create("http://www.w3.org/2000/01/rdf-schema#subPropertyOf"));
    labelMap.put("equivalentclass", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put("equivalentclasses", IRI.create("http://www.w3.org/2002/07/owl#equivalentClass"));
    labelMap.put(
        "equivalentproperty", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put(
        "equivalentproperties", IRI.create("http://www.w3.org/2002/07/owl#equivalentProperty"));
    labelMap.put("disjointwith", IRI.create("http://www.w3.org/2002/07/owl#disjointWith"));
  }
}
