package ocaml.editor.completion;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ocaml.OcamlPlugin;
import ocaml.editors.OcamlEditor;
import ocaml.parsers.OcamlDefinition;
import ocaml.parsers.OcamlInterfaceParser;
import ocaml.util.Misc;
import ocaml.util.OcamlPaths;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

/**
 * This Eclipse Job is used to parse the O'Caml library files in the background with a progress indicator, so
 * that the first completion is fast (the following ones are always going to be fast, because we cache the
 * results).
 * 
 * <p>
 * This class is also used in a static way to immediately compute completions (without running another
 * thread).
 */
public class CompletionJob extends Job {

	private static boolean bParsingInterfacesDone = false;

	private static FilenameFilter mliFilter = new FilenameFilter() {
		public boolean accept(File dir, String name) {
			return name.endsWith(".mli");
		}
	};

	private final IProject project;

	public CompletionJob(String name, IProject project) {
		super(name);
		this.project = project;
	}

	public static boolean isParsingFinished() {
		return bParsingInterfacesDone;
	}

	// we cache the definitions "super-tree" for a short time
	private static long lastTime = 0;

	private static OcamlDefinition lastDefinitionsRoot = null;

	private static IProject lastProject = null;

	/**
	 * The duration in milliseconds during which the definitions super-tree is kept in memory. If another
	 * completion is asked during this time the search won't get executed, and so the completion box will
	 * appear immediately but without the latest modifications done during this duration.
	 */
	private static final int cacheTime = 2000;

	/**
	 * Build the super-tree of all definitions found in mli files in the project directories (this normally
	 * includes the O'Caml standard library). This method is defined in class CompletionJob, but it is not a
	 * job, it is executed in the same thread as the caller.
	 */
	public static OcamlDefinition buildDefinitionsTree(IProject project, boolean bUsingEditor) {
		OcamlDefinition definitionsRoot = null;

		/*
		 * We use the super-tree in cache instead of rebuilding it if the last completion dates from less
		 * than a few seconds. This allows us to speed up typing in the completion box (if we didn't do this,
		 * the super-tree would be rebuilt for each character typed in the completion box)
		 */

		if (lastDefinitionsRoot != null && project.equals(lastProject)
				&& System.currentTimeMillis() - lastTime < cacheTime)
			definitionsRoot = lastDefinitionsRoot;
		else {
			// System.err.println("building definitions tree");
			/*
			 * Build a super-tree from all the definitions trees so as to be able to use a recursive function.
			 * The root of this super-tree contains all the modules accessible from the project + the members
			 * of opened modules ("Pervasives" being always opened by default)
			 */
			definitionsRoot = new OcamlDefinition(OcamlDefinition.Type.DefModule);
			OcamlInterfaceParser parser = OcamlInterfaceParser.getInstance();

			OcamlPaths ocamlPaths = new OcamlPaths(project);

			String[] paths = ocamlPaths.getPaths();
			// for each path in the project
			for (String path : paths) {

				if (path.equals(".")){
					IPath projectPath = project.getLocation();
					if (projectPath != null)
						path = projectPath.toOSString();
					else{
						OcamlPlugin.logError("Error in CompletionJob:buildDefinitionsTree : project location is null");
						continue;
					}
						
				}

				// try with a path relative to the project location
				// Go through the IResources API to follow possibly linked resources.
				IFolder folder = project.getFolder(path);
				IPath location = folder.getLocation();
				File dir = null;
				if(location != null)
					dir = new File(location.toOSString());				
				
				
				// try with an absolute path
				if (!(dir != null && dir.exists() && dir.isDirectory())) {
					dir = new File(path);
				}

				if (!(dir.exists() && dir.isDirectory()))
					continue;

				// get all the mli files from the directory
				String[] mliFiles = dir.list(mliFilter);

				// for each mli file
				for (String mliFile : mliFiles) {
					File file = new File(dir.getAbsolutePath() + File.separatorChar + mliFile);
					OcamlDefinition def = parser.parseFile(file);
					definitionsRoot.addChild(def);
				}
			}

			if (bUsingEditor) {

				// find all the opened modules and add their definitions tree to the root of the super-tree
				String[] openModules = null;
				try {

					IEditorPart editorPart = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().getActiveEditor();
					if (editorPart instanceof OcamlEditor) {
						OcamlEditor editor = (OcamlEditor) editorPart;

						String file = editor.getFileBeingEdited().getName();
						String moduleName = null;
						if (file.endsWith(".ml")) {
							// remove the file extension and change the first character to uppercase
							moduleName = "" + Character.toUpperCase(file.charAt(0))
									+ file.substring(1, file.length() - 3);
						}

						// get the text of the document currently opened in the editor
						String doc = editor.getDocumentProvider().getDocument(editor.getEditorInput()).get();
						openModules = findOpenModules(doc, moduleName);
					}
				} catch (Exception e) {
					OcamlPlugin.logError("ocaml plugin error", e);
				}

				if (openModules != null) {
					for (String module : openModules) {
						OcamlDefinition defModule = null;

						for (OcamlDefinition def : definitionsRoot.getChildren())
							if (def.getName().equals(module)
									&& def.getType().equals(OcamlDefinition.Type.DefModule)) {
								defModule = def;
								break;
							}

						if (defModule != null) {
							for (OcamlDefinition def : defModule.getChildren())
								definitionsRoot.addChild(def);
						}
					}
				}
			}

		}

		lastDefinitionsRoot = definitionsRoot;
		lastTime = System.currentTimeMillis();
		lastProject = project;

		return definitionsRoot;
	}

	static final Pattern patternOpen = Pattern.compile("(\\A|\\n) *open +(\\w*)");

	/** Find the opened modules by looking for "open moduleName" directives in the source code */
	private static String[] findOpenModules(String doc, String moduleName) {
		TreeSet<String> openModules = new TreeSet<String>();

		if (moduleName != null)
			openModules.add(moduleName);

		Matcher matcher = patternOpen.matcher(doc);
		while (matcher.find()) {
			String name = matcher.group(2).trim();
			if (name.endsWith(";;"))
				name = name.substring(0, name.length() - 2);
			openModules.add(name);
		}

		openModules.add("Pervasives");

		return openModules.toArray(new String[0]);
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		try {

			OcamlInterfaceParser parser = OcamlInterfaceParser.getInstance();

			// get all the mli files from the directory
			String[] mliFiles = null;

			// no project => parse O'Caml library files
			if (this.project == null) {
				File dir = new File(OcamlPlugin.getLibFullPath());
				if (!dir.exists() || !dir.isDirectory()) {
					OcamlPlugin.logWarning("Parsing of mli files aborted : directory not found");
					return Status.CANCEL_STATUS;
				}

				mliFiles = dir.list(mliFilter);
				for (int i = 0; i < mliFiles.length; i++)
					mliFiles[i] = dir.getAbsolutePath() + File.separatorChar + mliFiles[i];

			} else {
				IFile[] projectMliFiles = Misc.getMliFiles(project);
				ArrayList<String> strFiles = new ArrayList<String>();
				for (IFile file : projectMliFiles) {
					IPath path = file.getLocation();
					if (path != null)
						strFiles.add(path.toOSString());
				}

				mliFiles = strFiles.toArray(new String[0]);
			}

			if (mliFiles == null)
				return Status.CANCEL_STATUS;

			monitor.beginTask("Parsing mli files", mliFiles.length);

			Thread.yield();

			// for each mli file
			for (String mliFile : mliFiles) {
				if (monitor.isCanceled())
					return Status.CANCEL_STATUS;

				monitor.subTask(mliFile);

				File file = new File(mliFile);
				parser.parseFile(file);

				// for(int i = 0; i < 1000000000; i++);

				monitor.worked(1);
				Thread.yield();
			}
		} finally {

			monitor.done();
			bParsingInterfacesDone = true;
		}

		return Status.OK_STATUS;
	}
}
