package server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import main.Config;
import main.Main;
import project.Project;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by wenbo on 1/2/18.
 */
public class FirstRequestHandler implements HttpHandler {

	// gson builder
	private final Gson gson;

	public FirstRequestHandler() {
		gson = new GsonBuilder().create();
	}

	@Override
	public void handle(HttpExchange httpExchange) throws IOException {

		System.out.println("Serving /first");

		// check if this is a POST request
		if (! httpExchange.getRequestMethod().equalsIgnoreCase("POST")) {
			Server.sendResponse(httpExchange, HttpsURLConnection.HTTP_BAD_METHOD, "");
			return;
		}

		// get the project
		Project project = Main.getProject();

		// construct a response map
		Map<String, Object> respMap = new HashMap<>();
		respMap.put("initialViewportX", project.getInitialViewportX());
		respMap.put("initialViewportY", project.getInitialViewportY());
		respMap.put("initialPredicates", project.getInitialPredicates());
		respMap.put("viewportWidth", project.getViewportWidth());
		respMap.put("viewportHeight", project.getViewportHeight());
		respMap.put("initialCanvasId", project.getInitialCanvasId());
		respMap.put("tileH", Config.tileH);
		respMap.put("tileW", Config.tileW);
		respMap.put("renderingParams", project.getRenderingParams());

		// convert the response to a json object and send it back
		String response = gson.toJson(respMap);
		Server.sendResponse(httpExchange, HttpsURLConnection.HTTP_OK, response);
	}
}
