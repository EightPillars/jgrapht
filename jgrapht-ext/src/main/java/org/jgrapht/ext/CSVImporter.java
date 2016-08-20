package org.jgrapht.ext;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.jgrapht.Graph;
import org.jgrapht.WeightedGraph;

public class CSVImporter<V, E>
{
    private static final Character DEFAULT_DELIMITER = ';';

    private Format format;
    private VertexProvider<V> vertexProvider;
    private EdgeProvider<V, E> edgeProvider;
    private Character delimiter;
    private final Set<Parameter> parameters;

    /**
     * Formats of the importer.
     */
    public enum Format
    {
        /**
         * Edge list. Behaves the same as
         * {@link CSVImporter.Format#ADJACENCY_LIST}.
         */
        EDGE_LIST,

        /**
         * Adjacency list
         */
        ADJACENCY_LIST,

        /**
         * Matrix
         */
        MATRIX,
    }

    /**
     * Parameters that affect the behavior of the importer.
     */
    public enum Parameter
    {
        /**
         * Whether the input contains node ids. Only valid for the
         * {@link Format#MATRIX MATRIX} format.
         */
        MATRIX_FORMAT_NODEID,
        /**
         * Whether to input contains edge weights. Only valid for the
         * {@link Format#MATRIX MATRIX} format.
         */
        MATRIX_FORMAT_EDGE_WEIGHTS,
        /**
         * Whether the input contains zero as edge weight for missing edges.
         * Only valid for the {@link Format#MATRIX MATRIX} format.
         */
        MATRIX_FORMAT_ZERO_WHEN_NO_EDGE,
    }

    /**
     * Constructs a new importer.
     * 
     * @param vertexProvider provider for the generation of vertices. Must not
     *        be null.
     * @param edgeProvider provider for the generation of edges. Must not be
     *        null.
     */
    public CSVImporter(
        Format format,
        Character delimiter,
        VertexProvider<V> vertexProvider,
        EdgeProvider<V, E> edgeProvider)
    {
        this.format = format;
        if (vertexProvider == null) {
            throw new IllegalArgumentException(
                "Vertex provider cannot be null");
        }
        this.vertexProvider = vertexProvider;
        if (edgeProvider == null) {
            throw new IllegalArgumentException("Edge provider cannot be null");
        }
        this.edgeProvider = edgeProvider;
        this.delimiter = delimiter;
        this.parameters = new HashSet<>();
    }

    /**
     * Get the format that the importer is using.
     * 
     * @return the input format
     */
    public Format getFormat()
    {
        return format;
    }

    /**
     * Set the format of the importer
     * 
     * @param format the format to use
     */
    public void setFormat(Format format)
    {
        this.format = format;
    }

    /**
     * Return if a particular parameter of the exporter is enabled
     * 
     * @param p the parameter
     * @return {@code true} if the parameter is set, {@code false} otherwise
     */
    public boolean isParameter(Parameter p)
    {
        return parameters.contains(p);
    }

    /**
     * Set the value of a parameter of the exporter
     * 
     * @param p the parameter
     * @param value the value to set
     */
    public void setParameter(Parameter p, boolean value)
    {
        if (value) {
            parameters.add(p);
        } else {
            parameters.remove(p);
        }
    }

    /**
     * Import a graph.
     * 
     * <p>
     * The provided graph must be able to support the features of the graph that
     * is read. For example if the input contains self-loops then the graph
     * provided must also support self-loops. The same for multiple edges.
     * 
     * <p>
     * If the provided graph is a weighted graph, the importer also reads edge
     * weights.
     * 
     * @param graph the graph
     * @param input the input reader
     * @throws ImportException in case an error occurs, such as I/O or parse
     *         error
     */
    public void read(Graph<V, E> graph, Reader input)
        throws ImportException
    {
        switch (format) {
        case EDGE_LIST:
        case ADJACENCY_LIST:
            read(graph, input, new AdjacencyListCSVListener(graph));
            break;
        case MATRIX:
            read(graph, input, new MatrixCSVListener(graph));
            break;
        }
    }

    private void read(Graph<V, E> graph, Reader input, CSVBaseListener listener)
        throws ImportException
    {
        try {
            ThrowingErrorListener errorListener = new ThrowingErrorListener();

            // create lexer
            CSVLexer lexer = new CSVLexer(new ANTLRInputStream(input));
            lexer.setSep(delimiter);
            lexer.removeErrorListeners();
            lexer.addErrorListener(errorListener);

            // create parser
            CSVParser parser = new CSVParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(errorListener);

            // Specify our entry point
            CSVParser.FileContext graphContext = parser.file();

            // Walk it and attach our listener
            ParseTreeWalker walker = new ParseTreeWalker();
            walker.walk(listener, graphContext);
        } catch (IOException e) {
            throw new ImportException(
                "Failed to import CSV graph: " + e.getMessage(),
                e);
        } catch (ParseCancellationException pe) {
            throw new ImportException(
                "Failed to import CSV graph: " + pe.getMessage(),
                pe);
        } catch (IllegalArgumentException iae) {
            throw new ImportException(
                "Failed to import CSV graph: " + iae.getMessage(),
                iae);
        }
    }

    private class ThrowingErrorListener
        extends BaseErrorListener
    {

        @Override
        public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e)
            throws ParseCancellationException
        {
            throw new ParseCancellationException(
                "line " + line + ":" + charPositionInLine + " " + msg);
        }
    }

    // listener for the edge list format
    private class AdjacencyListCSVListener
        extends RowCSVListener
    {
        public AdjacencyListCSVListener(Graph<V, E> graph)
        {
            super(graph);
        }

        @Override
        protected void handleRow()
        {
            // first is source
            String sourceKey = row.get(0);
            if (sourceKey.isEmpty()) {
                throw new ParseCancellationException(
                    "Source vertex cannot be empty");
            }
            V source = vertices.get(sourceKey);
            if (source == null) {
                source = vertexProvider.buildVertex(sourceKey, new HashMap<>());
                vertices.put(sourceKey, source);
                graph.addVertex(source);
            }
            row.remove(0);

            // remaining are targets
            for (String key : row) {
                if (key.isEmpty()) {
                    throw new ParseCancellationException(
                        "Target vertex cannot be empty");
                }
                V target = vertices.get(key);

                if (target == null) {
                    target = vertexProvider.buildVertex(key, new HashMap<>());
                    vertices.put(key, target);
                    graph.addVertex(target);
                }

                try {
                    String label = "e_" + source + "_" + target;
                    E e = edgeProvider.buildEdge(
                        source,
                        target,
                        label,
                        new HashMap<String, String>());
                    graph.addEdge(source, target, e);
                } catch (IllegalArgumentException e) {
                    throw new ParseCancellationException(
                        "Provided graph does not support input: "
                            + e.getMessage(),
                        e);
                }
            }
        }

    }

    // listener for the edge list format
    private class MatrixCSVListener
        extends RowCSVListener
    {
        private boolean assumeNodeIds;
        private boolean assumeEdgeWeights;
        private boolean assumeZeroWhenNoEdge;
        private int verticesCount;
        private int currentVertex;

        public MatrixCSVListener(Graph<V, E> graph)
        {
            super(graph);
            this.assumeNodeIds = parameters
                .contains(Parameter.MATRIX_FORMAT_NODEID);
            this.assumeEdgeWeights = parameters
                .contains(Parameter.MATRIX_FORMAT_EDGE_WEIGHTS);
            this.assumeZeroWhenNoEdge = parameters
                .contains(Parameter.MATRIX_FORMAT_ZERO_WHEN_NO_EDGE);
            this.verticesCount = 0;
            this.currentVertex = 1;
        }

        @Override
        protected void handleRow()
        {
            if (assumeNodeIds) {
                row.remove(0);
            }

            if (header) {
                if (assumeNodeIds) {
                    createVerticesFromNodeIds();
                } else {
                    createVertices();
                    createEdges();
                    currentVertex++;
                }
            } else {
                createEdges();
                currentVertex++;
            }
        }

        private void createVerticesFromNodeIds()
        {
            // header line contains nodes
            verticesCount = row.size();
            if (verticesCount < 1) {
                throw new ParseCancellationException(
                    "Failed to parse header with nodes");
            }
            int key = 1;
            for (String id : row) {
                V vertex = vertexProvider.buildVertex(id, new HashMap<>());
                vertices.put(String.valueOf(key), vertex);
                graph.addVertex(vertex);
            }
        }

        private void createVertices()
        {
            // header line contains nodes
            verticesCount = row.size();
            if (verticesCount < 1) {
                throw new ParseCancellationException(
                    "Failed to parse header with nodes");
            }
            int key = 1;
            for (key = 1; key <= verticesCount; key++) {
                V vertex = vertexProvider
                    .buildVertex(String.valueOf(key), new HashMap<>());
                vertices.put(String.valueOf(key), vertex);
                graph.addVertex(vertex);
            }
        }

        private void createEdges()
        {
            if (row.size() != verticesCount) {
                throw new ParseCancellationException(
                    "Row contains fewer than " + verticesCount + " entries");
            }

            int target = 1;
            for (String entry : row) {
                // try to parse an integer
                try {
                    Integer entryAsInteger = Integer.parseInt(entry);
                    if (entryAsInteger == 0) {
                        if (!assumeZeroWhenNoEdge && assumeEdgeWeights) {
                            createEdge(currentVertex, target, 0d);
                        }
                    } else {
                        if (assumeEdgeWeights) {
                            createEdge(
                                currentVertex,
                                target,
                                Double.valueOf(entryAsInteger));
                        } else {
                            createEdge(currentVertex, target, null);
                        }

                    }
                    target++;
                    continue;
                } catch (NumberFormatException nfe) {
                    // nothing
                }

                // try to parse a double
                try {
                    Double entryAsDouble = Double.parseDouble(entry);
                    if (assumeEdgeWeights) {
                        createEdge(currentVertex, target, entryAsDouble);
                    } else {
                        throw new ParseCancellationException(
                            "Double entry found when expecting no weights");
                    }
                } catch (NumberFormatException nfe) {
                    // nothing
                }

                target++;
            }
        }

        private void createEdge(int s, int t, Double weight)
        {
            try {
                V source = vertices.get(String.valueOf(s));
                V target = vertices.get(String.valueOf(t));

                String label = "e_" + source + "_" + target;
                E e = edgeProvider.buildEdge(
                    source,
                    target,
                    label,
                    new HashMap<String, String>());
                graph.addEdge(source, target, e);

                if (weight != null) {
                    if (graph instanceof WeightedGraph<?, ?>) {
                        ((WeightedGraph<V, E>) graph).setEdgeWeight(e, weight);
                    }
                }
            } catch (IllegalArgumentException e) {
                throw new ParseCancellationException(
                    "Provided graph does not support input: " + e.getMessage(),
                    e);
            }
        }

    }

    // base listener
    private abstract class RowCSVListener
        extends CSVBaseListener
    {
        protected Graph<V, E> graph;
        protected List<String> row;
        protected Map<String, V> vertices;
        protected boolean header;

        public RowCSVListener(Graph<V, E> graph)
        {
            this.graph = graph;
            this.row = new ArrayList<>();
            this.vertices = new HashMap<>();
            this.header = false;
        }

        @Override
        public void enterHeader(CSVParser.HeaderContext ctx)
        {
            header = true;
        }

        @Override
        public void exitHeader(CSVParser.HeaderContext ctx)
        {
            header = false;
        }

        @Override
        public void enterRecord(CSVParser.RecordContext ctx)
        {
            row.clear();
        }

        @Override
        public void exitRecord(CSVParser.RecordContext ctx)
        {
            if (row.isEmpty()) {
                throw new ParseCancellationException("Empty CSV record");
            }

            handleRow();
        }

        @Override
        public void exitTextField(CSVParser.TextFieldContext ctx)
        {
            row.add(ctx.TEXT().getText());
        }

        @Override
        public void exitStringField(CSVParser.StringFieldContext ctx)
        {
            row.add(CSVUtils.unescapeCSV(ctx.STRING().getText(), delimiter));
        }

        @Override
        public void exitEmptyField(CSVParser.EmptyFieldContext ctx)
        {
            row.add("");
        }

        protected abstract void handleRow();

    }

}