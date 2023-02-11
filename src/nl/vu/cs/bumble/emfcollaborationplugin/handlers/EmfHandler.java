package nl.vu.cs.bumble.emfcollaborationplugin.handlers;



import static org.eclipse.emfcloud.modelserver.common.ModelServerPathParametersV2.FORMAT_JSON;
import static org.eclipse.emfcloud.modelserver.common.ModelServerPathParametersV2.FORMAT_JSON_V2;
import static org.eclipse.emfcloud.modelserver.common.ModelServerPathParametersV2.FORMAT_XMI;
import static org.eclipse.emfcloud.modelserver.common.ModelServerPathParametersV2.PATHS_URI_FRAGMENTS;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.common.util.WrappedException;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EFactory;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.change.util.ChangeRecorder;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.edit.domain.IEditingDomainProvider;
import org.eclipse.emfcloud.modelserver.client.ModelServerClient;
import org.eclipse.emfcloud.modelserver.client.Response;
import org.eclipse.emfcloud.modelserver.client.SubscriptionListener;
import org.eclipse.emfcloud.modelserver.client.SubscriptionOptions;
import org.eclipse.emfcloud.modelserver.client.v2.ModelServerClientV2;
import org.eclipse.emfcloud.modelserver.common.APIVersion;
import org.eclipse.emfcloud.modelserver.common.codecs.DefaultJsonCodec;
import org.eclipse.emfcloud.modelserver.common.codecs.EMFJsonConverter;
import org.eclipse.emfcloud.modelserver.common.codecs.EncodingException;
import org.eclipse.emfcloud.modelserver.example.client.ExampleEObjectSubscriptionListener;
import org.eclipse.emfcloud.modelserver.example.client.ExampleJsonStringSubscriptionListener;
import org.eclipse.emfcloud.modelserver.example.client.ExampleXMISubscriptionListener;
import org.eclipse.emfcloud.modelserver.example.util.PrintUtil;
import org.eclipse.emfcloud.modelserver.jsonpatch.JsonPatch;
import org.eclipse.emfcloud.modelserver.jsonpatch.Operation;
import org.eclipse.emfcloud.modelserver.jsonschema.Json;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nl.vu.cs.bumble.emfcollaborationplugin.Activator;
import nl.vu.cs.bumble.statemachine.Input;
import nl.vu.cs.bumble.statemachine.impl.StateMachineImpl;

public class EmfHandler extends AbstractHandler {
	
	private static final APIVersion API_VERSION = APIVersion.API_V2;
	private static final Integer STATUS_OK = 200;
	
	private ModelServerClient client = Activator.getModelServerClient();
	private ConvertHandler converter = ConvertHandler.getConverter();
	
	private String SERVER_ECORE_PATH = "";
	private String LOCAL_ECORE_PATH = "";
		
	@SuppressWarnings("all")
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
		IEditorPart editor = window.getActivePage().getActiveEditor();
		System.out.println(editor);
		if(editor instanceof IEditingDomainProvider) {
			EObject rootElement = this.getRootModel(editor);
			Resource resource = ((IEditingDomainProvider) editor).getEditingDomain().getResourceSet().getResources().get(0);
			
//			System.out.println(rootElement.eContents());
//			((StateMachineImpl) rootElement).setName("random");
//			try {
////				((StateMachineImpl) rootElement).eResource().getContents().add(rootElement);
//				((StateMachineImpl) rootElement).eResource().save(Collections.EMPTY_MAP);
//			} catch (IOException e1) {
//				// TODO Auto-generated catch block
//				e1.printStackTrace();
//			}
			
			try {
				this.setLocalEcorePath(rootElement);
			} catch (EncodingException e1) {
				e1.printStackTrace();
			}
			
			try {
				this.setServerEcorePath();
			} catch (IOException e1) {
				e1.printStackTrace();
			} catch (EncodingException e) {
				e.printStackTrace();
			}
			
			String modelUri = this.getNameOfModel(rootElement);
			System.out.println("model name: " + modelUri);
			
			
			if(this.isModelExistOnServer(modelUri)) {
				try {
					this.updateLocalModel(modelUri, rootElement);

				} catch (IOException e) {
					e.printStackTrace();
				}
				
			} else {
				try {
					this.addModelToModelInventory(modelUri, rootElement);
					
				} catch (EncodingException e) {
					e.printStackTrace();
				}
			}	
			
			LocalChangeListenerSwitch listenerSwitch = LocalChangeListenerSwitch.getInstance();
			
			ChangeHandler recorder = new ChangeHandler(resource, client, modelUri, LOCAL_ECORE_PATH, listenerSwitch);
					
			SubscriptionListener listener = new ExampleEObjectSubscriptionListener(modelUri, API_VERSION) {
				   public void onIncrementalUpdate(final JsonPatch patch) {
					      printResponse(
					         "Incremental <JsonPatch> update from model server received:\n" + PrintUtil.toPrettyString(patch));	
					      	
					      	  if(listenerSwitch.isActivated()) {
					      		  listenerSwitch.switchOff();
					      		  executeJsonPatch(patch, editor);
					      		  
					      	  } else {
					      		  listenerSwitch.switchOn();
					      	  }
					   }
			};
			           
			client.subscribe(modelUri, listener);						
		}
			return null;
		
	};
	
	private void executeJsonPatch(JsonPatch patches, IEditorPart editor) {
		EObject model = getRootModel(editor);
		
		for(int i = 0; i < patches.getPatch().size(); i++) {
			Operation patch = patches.getPatch().get(i);
			String op = patch.getOp().toString();
			
			if(op == "replace") {
				this.applyReplacePatch(model, patch);
			}
			if(op == "remove") {
				this.applyRemovePatch(model, patch);
			}
			if(op == "add") {
				this.applyAddPatch(model, patch);
			}
		}
		
		try {
			model.eResource().getContents().add(model);
			model.eResource().save(Collections.EMPTY_MAP);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		
	}
	
	private EObject createNewObject(EObject model, Operation patch) {
		String className = "";
		
		//TODO: sometimes the patch is converted to a JsonNode without value
		try {
			JsonNode patchJson = converter.objectToJsonNode(patch);
			JsonNode valueNode = patchJson.get("value");
			className = valueNode.get("value").get("eClass").asText();
		
		} catch (EncodingException e) {
			e.printStackTrace();
		}
		
		className = this.convertEClassTypeToLocal(className);
		
		// TODO: can be incorrect 
		className = className.split("#//")[1];
		
		System.out.println("class name : " + className);
		

		EPackage modelPackage = model.eClass().getEPackage();		
		EClassifier classif = modelPackage.getEClassifier(className);
		System.out.println("classifier: " + classif);
		
		EFactory modelFactory = modelPackage.getEFactoryInstance();
		
		EObject newObj = null;
		
		if (classif != null && classif instanceof EClass) {
			  newObj = modelFactory.create((EClass) classif);		 
		}
		
		return newObj;
	}
	
	private EObject findObjToPatch(EObject model, String[] paths, int len) {
		EObject objToPatch = model.eContents().get(0);
		String rootClassName = paths[1];
		int rootPosition = Integer.parseInt(paths[2]);
		
		EList<EObject> contents = model.eContents();
		
		int counter = 0;
				
		for(int i = 0; i < contents.size(); i++) {
			EObject rootNode = model.eContents().get(i);
			String objClassName = rootNode.eContainmentFeature().getName();
			
			if( objClassName.equals(rootClassName)) {				
				if(counter == rootPosition) {
					objToPatch = rootNode;
					break;
				} else {
					counter++;
				}
			}
		}
		
		paths = Arrays.copyOfRange(paths, 3, paths.length);

		while(paths.length > len) {
			int subNodePosition = Integer.parseInt(paths[1]);
			
			paths = Arrays.copyOfRange(paths, 2, paths.length);
			objToPatch = objToPatch.eContents().get(subNodePosition);
		}
		
		return objToPatch;
	}
		
	@SuppressWarnings("unchecked")
	private void applyAddPatch(EObject model, Operation patch) {
			
		EObject newObj = this.createNewObject(model, patch);
				
		String path = patch.getPath();		
		String[] paths = path.split("/");
		
		EObject objToPatch = model;
		
		if (!paths[2].equals("-")) {
			objToPatch = this.findObjToPatch(model, paths, 2);
		}
		
		// path: /input/-
		String featureName = paths[paths.length - 2];
		
		EStructuralFeature feature = objToPatch.eClass().getEStructuralFeature(featureName);
		System.out.println("feature get: " + feature);
		
		EList<EObject> list =(EList<EObject>)objToPatch.eGet(feature);
		list.add(newObj);
	}
	
	private void applyRemovePatch(EObject model, Operation patch) {
		String path = patch.getPath();		
		String[] paths = path.split("/");
		
		EObject objToPatch = this.findObjToPatch(model, paths, 0);
		
		EcoreUtil.delete(objToPatch);
		
	}
	
	private void applyReplacePatch(EObject model, Operation patch) {
		String path = patch.getPath();		
		String[] paths = path.split("/");
				
		String featureName = paths[paths.length - 1];
		String newValue = "";
		
		try {
			JsonNode patchJson = converter.objectToJsonNode(patch);
			JsonNode valueNode = patchJson.get("value");
			
//			String valueType = valueNode.get("eClass").asText();
			newValue = valueNode.get("value").asText();
			
//			System.out.println("new value type: " + valueType);
//			System.out.println("new value: " + newValue);
			
		} catch (EncodingException e) {
			e.printStackTrace();
		}
		
		EObject objToPatch = this.findObjToPatch(model, paths, 1);
		
		System.out.println("featureName: "+ featureName);
		objToPatch.eSet(objToPatch.eClass().getEStructuralFeature(featureName), newValue);
	}
			
	private void addModelToModelInventory(String modelUri, EObject model) throws EncodingException {
		String payload = this.convertEClassTypeToWorkspace(model);
		Response<String> response = client.create(modelUri, payload).join();
	}
	
	private Boolean isModelExistOnServer(String modelUri) {		
		Response<String> response = client.get(modelUri).join();
		return response.getStatusCode().equals(STATUS_OK);
	}
		
	private void updateLocalModel(String modelUri, EObject model) throws IOException {
		String response = this.getModelFromModelInventory(modelUri);
		String converted = this.convertEClassTypeToLocal(response);
		EObject updatedModel = client.decode(converted, FORMAT_JSON_V2).get();
		
		
		try {
		 model.eResource().getContents().set(0, updatedModel);		
		 updatedModel.eResource().save(Collections.EMPTY_MAP);
		 
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}
	
	public String getModelFromModelInventory(String modelUri) throws IOException {
		Response<String> response = client.get(modelUri).join();
		String result = response.body();
		
		return result;
	}
	
	private void setLocalEcorePath(EObject model) throws EncodingException {
		JsonNode jsonRoot = converter.objectToJsonNode(model);
        String path = jsonRoot.get("eClass").asText().split("#//")[0];
        
        this.LOCAL_ECORE_PATH = path;
	}
	
	private void setServerEcorePath() throws IOException, EncodingException {
		String path = "";
		String response = this.getModelFromModelInventory("EcoreURI.xmi");
		
		EObject obj = client.decode(response, FORMAT_JSON_V2).get();
		JsonNode json = converter.objectToJsonNode(obj);
		String raw = json.get("eClass").toString().split("#//")[0];
		path = raw.substring(1);
		
		this.SERVER_ECORE_PATH = path;
	}
	
	private String convertEClassTypeToWorkspace(EObject model) throws EncodingException {
		String json = client.encode(model, FORMAT_JSON_V2);
		
//		System.out.println("local: " + LOCAL_ECORE_PATH);
//		System.out.println("server: " + SERVER_ECORE_PATH);
		
		String converted = json.replace(LOCAL_ECORE_PATH, SERVER_ECORE_PATH);
//		String converted = json; 
		
		return converted;
	}
	
	private String convertEClassTypeToLocal(String model) {
		String converted = model.replace(SERVER_ECORE_PATH, LOCAL_ECORE_PATH);
		
//		System.out.println("local: " + LOCAL_ECORE_PATH);
//		System.out.println("server: " + SERVER_ECORE_PATH);
		
		return converted;
//		return model;
	}
	
	private String getNameOfModel(EObject model) {
		String name = model.eResource().getURI().lastSegment();
//		String name = "TrafficSignals.statemachine";
		return name;
	}
	
	private EObject getRootModel(IEditorPart editor) {
		return (EObject) ((IEditingDomainProvider) editor).getEditingDomain().getResourceSet().getResources().get(0).getContents().get(0);
	}
	
}
