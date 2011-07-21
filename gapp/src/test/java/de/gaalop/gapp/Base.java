package de.gaalop.gapp;

import de.gaalop.CodeGenerator;
import de.gaalop.CodeGeneratorException;
import de.gaalop.CodeGeneratorPlugin;
import de.gaalop.CodeParser;
import de.gaalop.CodeParserException;
import de.gaalop.InputFile;
import de.gaalop.gapp.executer.Executer;
import de.gaalop.tba.Plugin;
import java.util.HashMap;
import de.gaalop.OptimizationException;
import de.gaalop.OutputFile;
import de.gaalop.cfg.ControlFlowGraph;
import de.gaalop.codegenGapp.GAPPCodeGeneratorPlugin;
import de.gaalop.gapp.importing.GAPPImportingMain;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides methods for testing the GAPP importing pass with a GAPPTestable object
 * @author christian
 */
public class Base {

    /**
     * Compiles and execute the GAPP code in a program, defined in a given GAPPTestable object
     * @param testable The GAPPTestable object which defines the program to be tested
     * @param cluName The name of the clucalc script to be tested
     * @return The executer object which was used for executing
     * @throws OptimizationException
     * @throws CodeParserException
     */
     protected Executer executeProgramm(GAPPTestable testable, String cluName) throws OptimizationException, CodeParserException {
        CodeParser parser = (new de.gaalop.clucalc.input.Plugin()).createCodeParser();
        ControlFlowGraph graph = parser.parseFile(new InputFile(cluName, testable.getSource()));

        GAPPImportingMain importer = new GAPPImportingMain();
        importer.importGraph(graph);

        //Evaluate!
        HashMap<String, Float> inputValues = testable.getInputs();
        Plugin plugin = new Plugin();
        de.gaalop.tba.cfgImport.CFGImporter importer2 = new de.gaalop.tba.cfgImport.CFGImporter(true,plugin);
        
        outputPlugin(new GAPPCodeGeneratorPlugin(), graph);
        outputPlugin(new de.gaalop.clucalc.output.Plugin(), graph);

        Executer executer = new Executer(importer2.getUsedAlgebra(),inputValues);
        graph.accept(executer);
        return executer;
    }

    /**
     * Writes the output of a plugin, called for a given graph, into files
     * @param plugin The plugin to be used
     * @param graph The underlying graph
     */
    public static void outputPlugin(CodeGeneratorPlugin plugin, ControlFlowGraph graph) {
        CodeGenerator generator = plugin.createCodeGenerator();
        Set<OutputFile> outputFiles;
        try {
            outputFiles = generator.generate(graph);
            for (OutputFile outputFile: outputFiles)
                writeFile(outputFile);
        } catch (CodeGeneratorException ex) {
            Logger.getLogger(GAPPImportingMain.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Writes an output file in the generatedTests directory
     * @param outputFile The file to be outputted
     */
    protected static void writeFile(OutputFile outputFile) {
        try {
            PrintWriter out = new PrintWriter("src/test/java/de/gaalop/gapp/generatedTests/"+outputFile.getName());
            out.print(outputFile.getContent());
            out.close();
        } catch (FileNotFoundException ex) {
            Logger.getLogger(GAPPImportingMain.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}
