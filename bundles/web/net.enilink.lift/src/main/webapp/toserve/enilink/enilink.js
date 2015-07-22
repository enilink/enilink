enilink = $.extend(window.enilink || {}, {
	param : function(name, noDecode) {
		name = name.replace(/[\[]/, "\\\[").replace(/[\]]/, "\\\]");
		var regex = new RegExp("[\\?&]" + name + "=([^&#]*)");
		var results = regex.exec(window.location.search);
		if (results == null)
			return undefined;
		return noDecode ? results[1] : decodeURIComponent(results[1].replace(/\+/g, " "));
	},

	params : function(noDecode) {
		var query = window.location.search;
		var regex = /[\\?&]([^=]+)=([^&#]*)/g;
		var result;
		var params = {};
		while ((result = regex.exec(query)) !== null) {
			params[result[1]] = noDecode ? result[2] : decodeURIComponent(result[2].replace(/\+/g, " "));
		}
		return params;
	},

	encodeParams : function(params) {
		var paramStr = "";
		$.each(params, function(name, value) {
			if (paramStr.length > 0) {
				paramStr += "&";
			}
			paramStr += name + "=" + encodeURIComponent(value);
		});
		return paramStr;
	},
	
	contextModel : function(node) {
		if (node) {
			node = (node instanceof jQuery) ? node : $(node);
			return node.closest("[data-model]").attr("data-model") || this.param("model");
		} else {
			return this.param("model");
		}
	},
	
	contextResource : function(node) {
		if (node) {
			node = (node instanceof jQuery) ? node : $(node);
			return node.closest("[data-resource]").attr("data-resource") || this.param("resource");
		} else {
			return this.param("resource");
		}
	}
});