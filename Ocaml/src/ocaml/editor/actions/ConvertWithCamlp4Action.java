package ocaml.editor.actions;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import ocaml.OcamlPlugin;
import ocaml.editors.OcamlEditor;
import ocaml.editors.lex.OcamllexEditor;
import ocaml.editors.yacc.OcamlyaccEditor;
import ocaml.exec.CommandRunner;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;

/** This action transforms a revised syntax file to a standard syntax file using camlp4 */
public class ConvertWithCamlp4Action implements IWorkbenchWindowActionDelegate {

	private IWorkbenchWindow window;

	public void run(IAction action) {
		IWorkbenchPage page = window.getActivePage();
		if (page != null) {
			IEditorPart editorPart = page.getActiveEditor();
			if (editorPart != null) {
				if (editorPart instanceof OcamlEditor) {
					OcamlEditor editor = (OcamlEditor) editorPart;
					
					String text = editor.getTextViewer().getDocument().get();
					if(text.trim().equals(""))
						return;

					IFile ifile = editor.getFileBeingEdited();
					
					if(ifile == null)
						return;

					/* Create a temporary file so that we don't have to save the editor */
					File tempFile = null;
					try {
						tempFile = File.createTempFile(ifile.getName(), "." + ifile.getLocation().getFileExtension());
						FileWriter writer = new FileWriter(tempFile);
						writer.append(text);
						writer.flush();
						writer.close();
					} catch (IOException e) {
						OcamlPlugin.logError("couldn't create temporary file for formatting with camlp4", e);
					}
					
					
					if (tempFile != null) {
						String[] command = new String[6];
						command[0] = OcamlPlugin.getCamlp4FullPath();
						command[1] = "-parser";
						command[2] = "OCamlr";
						command[3] = "-printer";
						command[4] = "OCaml";
						command[5] = tempFile.getPath();

						CommandRunner cmd = new CommandRunner(command, null);
						String result = cmd.getStdout();
						String errors = cmd.getStderr();
						
						/* If the result is an empty string, then camlp4 couldn't format correctly */
						if(result.trim().equals("")){
							MessageDialog.openInformation(null, "Ocaml Plugin", "Couldn't convert because of syntax errors\n" + errors);
							return;
						}

						Point sel = editor.getTextViewer().getSelectedRange();
						IDocument doc = editor.getTextViewer().getDocument();
						try {
							doc.replace(0, doc.getLength(), result);
						} catch (BadLocationException e1) {
							OcamlPlugin.logError("bad location while formatting with camlp4", e1);
							return;
						}

						editor.selectAndReveal(sel.x, sel.y);
					}

				} else if (editorPart instanceof OcamllexEditor) {
					//OcamllexEditor editor = (OcamllexEditor) editorPart;

				} else if (editorPart instanceof OcamlyaccEditor) {
					//OcamlyaccEditor editor = (OcamlyaccEditor) editorPart;

				} else
					OcamlPlugin.logError("ConvertWithCamlp4: not an Ocaml editor");

			} else
				OcamlPlugin.logError("ConvertWithCamlp4: editorPart is null");
		} else
			OcamlPlugin.logError("ConvertWithCamlp4: page is null");

	}

	public void dispose() {
	}

	public void init(IWorkbenchWindow window) {
		this.window = window;
	}

	public void selectionChanged(IAction action, ISelection selection) {
	}

}
